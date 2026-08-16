package com.lsd.wififrankenstein.ui.handshakecapture

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.network.NetworkException
import com.lsd.wififrankenstein.network.OhcClient
import com.lsd.wififrankenstein.network.WpaSecClient
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.util.ApMetadata
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.HandshakeCaptureRunner
import com.lsd.wififrankenstein.util.HandshakeHash
import com.lsd.wififrankenstein.util.HandshakeParser
import com.lsd.wififrankenstein.util.HandshakeType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.WpaCracker
import com.lsd.wififrankenstein.util.WpaSecDictManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class SortMode { DATE_DESC, DATE_ASC, NAME_ASC, NAME_DESC }

class HandshakeStorageViewModel(application: Application) : AndroidViewModel(application) {

    data class HcxpcapngtoolResult(
        val valid: Boolean,
        val eapolCount: Int,
        val pmkidCount: Int,
        val packetsTotal: Int,
        val durationSec: Int,
        val essid: String,
        val bssid: String,
        val channel: Int,
        val rawOutput: String
    )

    data class UploadResult(
        val success: Boolean,
        val message: String,
        val httpCode: Int = 0
    )

    private val storageManager = HandshakeStorageManager(application)
    private val captureRunner = HandshakeCaptureRunner(application)
    private val chrootManager = ChrootManager.get(application)
    private val wpaSecClient = WpaSecClient(application)
    private val wpaSecDictManager = WpaSecDictManager(application)
    private val importManager = HandshakeImportManager(application)
    private val ohcClient = OhcClient(application)
    private val tag = "HandshakeStorageVM"

    companion object {
        private const val PREFS_NAME = "handshake_upload"
        private const val KEY_OHC_EMAIL = "onlinehashcrack_email"
        private const val KEY_WPASEC_KEY = "wpasec_api_key"
        private const val URL_ONLINEHASHCRACK = "https://api.onlinehashcrack.com"
        private const val URL_WPASEC = "https://wpa-sec.stanev.org"
    }

    private fun getUploadPrefs(): SharedPreferences =
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSavedEmail(): String? = getUploadPrefs().getString(KEY_OHC_EMAIL, null)
    fun getSavedWpaSecKey(): String? = wpaSecClient.getSavedKey()
    fun saveEmail(email: String) {
        getUploadPrefs().edit().putString(KEY_OHC_EMAIL, email).apply()
    }

    fun saveWpaSecKey(key: String) {
        wpaSecClient.saveKey(key)
    }

    private val _wpaSecResult = MutableLiveData<Pair<String, String>?>(null)
    val wpaSecResult: LiveData<Pair<String, String>?> = _wpaSecResult

    private val _wpaSecCheckDone = MutableLiveData(false)
    val wpaSecCheckDone: LiveData<Boolean> = _wpaSecCheckDone

    private val _storageItems = MutableLiveData<List<HandshakeItem>>(emptyList())
    val storageItems: LiveData<List<HandshakeItem>> = _storageItems

    private val _storageCrackResult = MutableLiveData<Pair<String, String>?>()
    val storageCrackResult: LiveData<Pair<String, String>?> = _storageCrackResult

    private val _hcxpcapngtoolResult = MutableLiveData<HcxpcapngtoolResult?>(null)
    val hcxpcapngtoolResult: LiveData<HcxpcapngtoolResult?> = _hcxpcapngtoolResult

    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    private val _sortMode = MutableLiveData(SortMode.DATE_DESC)
    val sortMode: LiveData<SortMode> = _sortMode

    private val _isMultiSelectMode = MutableLiveData(false)
    val isMultiSelectMode: LiveData<Boolean> = _isMultiSelectMode

    private val _selectedFilePaths = MutableLiveData<Set<String>>(emptySet())
    val selectedFilePaths: LiveData<Set<String>> = _selectedFilePaths

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _orphanFiles = MutableLiveData<List<HandshakeStorageManager.OrphanFile>?>(null)
    val orphanFiles: LiveData<List<HandshakeStorageManager.OrphanFile>?> = _orphanFiles

    private val _orphanImportRunning = MutableLiveData(false)
    val orphanImportRunning: LiveData<Boolean> = _orphanImportRunning

    private val _manageStoragePermissionRequired = MutableLiveData(false)
    val manageStoragePermissionRequired: LiveData<Boolean> = _manageStoragePermissionRequired

    fun checkStoragePermission() {
        val required = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
                !android.os.Environment.isExternalStorageManager() &&
                !ChrootCapabilities.hasChrootTools(getApplication()) &&
                !ChrootCapabilities.isRootAvailable(getApplication())
        _manageStoragePermissionRequired.postValue(required)
    }

    private var allItems: List<HandshakeItem> = emptyList()

    fun loadStorage() {
        Log.d(tag, "loadStorage: loading...")
        _isLoading.postValue(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = storageManager.listHandshakes()
                Log.d(tag, "loadStorage: loaded ${items.size} items")
                val enriched = items.map { item ->
                    val cracked = storageManager.getCrackedPassword(item.bssid)
                    if (cracked != null && item.crackedPassword == null) {
                        storageManager.updateHandshakeCracked(item.fileName, cracked)
                    }
                    item.copy(crackedPassword = cracked ?: item.crackedPassword)
                }
                allItems = enriched
                applyFilters()
                _isLoading.postValue(false)
                extractMissingHashes(enriched)
                checkFileExistence(enriched)
                detectOrphanFiles()
                checkStoragePermission()
            } catch (e: Exception) {
                Log.e(tag, "Failed to load storage", e)
                _storageItems.postValue(emptyList())
                _isLoading.postValue(false)
            }
        }
    }

    private suspend fun checkFileExistence(items: List<HandshakeItem>) {
        try {
            val updated = storageManager.checkFileExistence(items)
            val changed =
                updated.filter { it.fileExists != items.find { i -> i.fileName == it.fileName }?.fileExists }
            if (changed.isNotEmpty()) {
                val updatedMap = updated.associateBy { it.fileName }
                allItems = allItems.map {
                    updatedMap[it.fileName]?.let { u -> it.copy(fileExists = u.fileExists) } ?: it
                }
                applyFilters()
            }
        } catch (e: Exception) {
            Log.w(tag, "checkFileExistence failed", e)
        }
    }

    private suspend fun extractHashesFromFile(
        fileName: String,
        chrootPath: String
    ): ParseResult {
        val allHashes = mutableListOf<HandshakeHash>()


        try {
            val parsed = captureRunner.readCapBytesAndParse(chrootPath)
            if (parsed.isNotEmpty()) {
                allHashes.addAll(parsed)
            }
        } catch (_: Exception) {
        }


        if (ChrootCapabilities.hasChrootTools(getApplication())) {
            try {
                val raw = captureRunner.getHcxpcapngtoolOutput(chrootPath)
                val parsed = raw.lines().mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                allHashes.addAll(parsed)
            } catch (_: Exception) {
            }
        }

        val distinct = if (allHashes.isNotEmpty()) allHashes.distinctBy { it.dedupKey() }
            .toList() else emptyList()
        return buildParseResult(distinct, fileName, chrootPath)
    }

    private data class ParseResult(
        val allHashes: List<HandshakeHash>,
        val hash22000Lines: List<String>,
        val hash22000: String?,
        val hashPmkid: String?,
        val eapolCount: Int,
        val pmkidCount: Int,
        val handshakeCount: Int,
        val hashDedupMd5: String?,
        val first: HandshakeHash?,
        val fileSize: Long
    )

    private suspend fun buildParseResult(
        allHashes: List<HandshakeHash>,
        fileName: String,
        chrootPath: String
    ): ParseResult {
        val hash22000Lines = allHashes.map { it.to22000Line() }.distinct()
        val hash22000 = hash22000Lines.joinToString("\n").takeIf { it.isNotBlank() }
        val validPmkidHashes =
            allHashes.filter { it.type == HandshakeType.PMKID && !it.isUselessPmkid }
        val hashPmkid = validPmkidHashes.map { it.pmkidOrMic }.filter { it.length == 32 }
            .joinToString("\n").takeIf { it.isNotBlank() }
        val eapolCount = allHashes.count { it.type == HandshakeType.EAPOL }
        val pmkidCount = allHashes.count { it.type == HandshakeType.PMKID }
        val handshakeCount = allHashes.size
        val first = allHashes.firstOrNull()
        val hashDedupMd5 = first?.dedupKey()
        val stat = chrootManager.executeInChroot("stat -c '%s' '$chrootPath' 2>/dev/null")
        val fileSize = stat.out.firstOrNull()?.trim()?.toLongOrNull()
            ?: File(
                HandshakeStorageManager.STORAGE_DIR.replaceFirst("/sdcard", "/storage/emulated/0"),
                fileName
            ).length()

        return ParseResult(
            allHashes = allHashes,
            hash22000Lines = hash22000Lines,
            hash22000 = hash22000,
            hashPmkid = hashPmkid,
            eapolCount = eapolCount,
            pmkidCount = pmkidCount,
            handshakeCount = handshakeCount,
            hashDedupMd5 = hashDedupMd5,
            first = first,
            fileSize = fileSize
        )
    }

    private fun extractMissingHashes(items: List<HandshakeItem>) {
        val missing = items.filter {
            it.fileExists && (it.hash22000 == null || it.isValid == false) && it.filePath.let { p ->
                p.endsWith(".cap") || p.endsWith(".pcap") || p.endsWith(".pcapng")
            }
        }
        if (missing.isEmpty()) return
        for (item in missing.take(3)) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val chrootPathForFile = item.filePath
                    val nativeHashes = captureRunner.readCapBytesAndParse(chrootPathForFile)

                    val hcxHashes = if (ChrootCapabilities.hasChrootTools(getApplication())) {
                        try {
                            val chrootPath =
                                storageManager.ensureChrootCopy(item.filePath) ?: return@launch
                            val raw = captureRunner.getHcxpcapngtoolOutput(chrootPath)
                            raw.lines().mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else emptyList()

                    val allHashes = (nativeHashes + hcxHashes).distinctBy { it.dedupKey() }
                    if (allHashes.isEmpty()) {
                        Log.d(
                            tag,
                            "extractMissingHashes: ${item.fileName}: no hashes found (native=${nativeHashes.size}, hcx=${hcxHashes.size})"
                        )
                        storageManager.updateHandshakeValid(item.fileName, false)
                        return@launch
                    }

                    val hash22000 = allHashes.map { it.to22000Line() }.distinct()
                        .joinToString("\n").takeIf { it.isNotBlank() }
                    val validPmkidHashes =
                        allHashes.filter { it.type == HandshakeType.PMKID && !it.isUselessPmkid }
                    val hashPmkid =
                        validPmkidHashes.map { it.pmkidOrMic }.filter { it.length == 32 }
                            .joinToString("\n").takeIf { it.isNotBlank() }
                    val hash16800 = allHashes.firstOrNull { it.type == HandshakeType.PMKID }
                        ?.let { HandshakeParser.convertTo16800(it) }

                    Log.d(
                        tag,
                        "extractMissingHashes: ${item.fileName}: native=$nativeHashes hcx=$hcxHashes merged=${allHashes.size} has22000=${hash22000 != null} hasPmkid=${hashPmkid != null} has16800=${hash16800 != null}"
                    )

                    if (hash22000 != null) storageManager.updateHandshakeHash22000(
                        item.fileName,
                        hash22000
                    )
                    if (hashPmkid != null) storageManager.updateHandshakeHashPmkid(
                        item.fileName,
                        hashPmkid
                    )
                    storageManager.updateHandshakeValid(item.fileName, true)

                    val firstHash = allHashes.firstOrNull()
                    if (firstHash != null) {
                        storageManager.updateHandshakeEssid(item.fileName, firstHash.essid)
                        storageManager.updateHandshakeBssid(
                            item.fileName,
                            firstHash.macAp.uppercase()
                        )
                        storageManager.updateHandshakeKeyver(item.fileName, firstHash.keyver)
                        storageManager.updateHandshakeCounts(
                            item.fileName,
                            allHashes.count { it.type == HandshakeType.EAPOL },
                            allHashes.count { it.type == HandshakeType.PMKID },
                            allHashes.size
                        )
                    }
                    storageManager.updateHandshakeOriginalFormat(
                        item.fileName,
                        item.fileName.substringAfterLast('.')
                    )

                    try {
                        val meta = captureRunner.readCapApMetadata(chrootPathForFile)
                        val apsInFile = buildApsInFile(meta)
                        if (apsInFile != null) storageManager.updateHandshakeApsInFile(
                            item.fileName,
                            apsInFile
                        )
                        val targetBssid = firstHash?.macAp?.uppercase()
                        val ap = meta.entries.firstOrNull { (bssid, _) ->
                            bssid.equals(targetBssid, ignoreCase = true)
                        }?.value ?: meta.values.firstOrNull()
                        if (ap != null) {
                            val clients =
                                if (ap.clients.isNotEmpty()) ap.clients.joinToString(",") else null
                            Log.d(
                                tag,
                                "extractMissingHashes: metadata for ${item.fileName}: ch=${ap.channel} akm=${ap.akm} cipher=${ap.pairwiseCipher} clients=[$clients]"
                            )
                            storageManager.updateHandshakeMetadata(
                                item.fileName,
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
                    tryCrackInBackground(item)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilters()
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        applyFilters()
    }

    fun setMultiSelectMode(enabled: Boolean) {
        _isMultiSelectMode.value = enabled
        if (!enabled) _selectedFilePaths.value = emptySet()
    }

    fun toggleSelection(filePath: String) {
        val current = _selectedFilePaths.value?.toMutableSet() ?: mutableSetOf()
        if (current.contains(filePath)) current.remove(filePath) else current.add(filePath)
        _selectedFilePaths.value = current
    }

    fun selectAll() {
        val filtered = _storageItems.value ?: return
        _selectedFilePaths.value = filtered.map { it.filePath }.toSet()
    }

    fun clearSelection() {
        _selectedFilePaths.value = emptySet()
    }

    private suspend fun detectOrphanFiles() {
        try {
            val orphans = storageManager.getOrphanFiles()
            if (orphans.isNotEmpty()) {
                _orphanFiles.postValue(orphans)
            }
        } catch (e: Exception) {
            Log.e(tag, "detectOrphanFiles failed", e)
        }
    }

    fun importOrphanFiles() {
        val orphans = _orphanFiles.value ?: return
        _orphanImportRunning.postValue(true)
        viewModelScope.launch(Dispatchers.IO) {
            var imported = 0
            var failed = 0
            for (orphan in orphans) {
                try {
                    val result = extractHashesFromFile(orphan.fileName, orphan.filePath)
                    val first = result.first
                    val essidFromFile = first?.essid?.takeIf { it.isNotBlank() }
                        ?: parseEssidFromFileName(orphan.fileName)
                    val bssidFromFile = first?.macAp?.takeIf { it.isNotBlank() }
                        ?: parseBssidFromFileName(orphan.fileName)
                    val keyverFromFile = first?.keyver
                    val hasValidData =
                        result.handshakeCount > 0 || (result.pmkidCount > 0 && result.hashPmkid != null)

                    storageManager.saveHandshakeMetadata(
                        HandshakeItem(
                            filePath = orphan.filePath,
                            fileName = orphan.fileName,
                            bssid = bssidFromFile,
                            essid = essidFromFile,
                            fileSize = result.fileSize,
                            lastModified = System.currentTimeMillis(),
                            hash22000 = result.hash22000,
                            hashPmkid = result.hashPmkid,
                            originalFormat = orphan.fileName.substringAfterLast('.'),
                            handshakeCount = result.handshakeCount,
                            eapolCount = result.eapolCount,
                            pmkidCount = result.pmkidCount,
                            hashDedupMd5 = result.hashDedupMd5,
                            isValid = if (hasValidData) null else false,
                            keyver = keyverFromFile
                        )
                    )
                    tryCrackInBackground(
                        HandshakeItem(
                            filePath = orphan.filePath,
                            fileName = orphan.fileName,
                            bssid = bssidFromFile,
                            essid = essidFromFile,
                            fileSize = result.fileSize,
                            lastModified = System.currentTimeMillis(),
                            hash22000 = result.hash22000,
                            hashPmkid = result.hashPmkid,
                            originalFormat = orphan.fileName.substringAfterLast('.'),
                            handshakeCount = result.handshakeCount,
                            eapolCount = result.eapolCount,
                            pmkidCount = result.pmkidCount,
                            hashDedupMd5 = result.hashDedupMd5,
                            isValid = if (hasValidData) null else false,
                            keyver = keyverFromFile
                        )
                    )
                    imported++
                } catch (e: Exception) {
                    Log.e(tag, "importOrphanFile failed: ${orphan.fileName}", e)
                    failed++
                }
            }
            _orphanImportRunning.postValue(false)
            _orphanFiles.postValue(null)
            if (imported > 0) {
                Log.d(tag, "importOrphanFiles: imported=$imported failed=$failed")
            }
            loadStorage()
        }
    }

    fun dismissOrphanFiles() {
        _orphanFiles.postValue(null)
    }

    enum class RefreshMode { SCAN_ONLY, REPARSE_ALL }

    fun refreshStorage(mode: RefreshMode) {
        _isLoading.postValue(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {

                val orphans = storageManager.getOrphanFiles()
                Log.d(tag, "refreshStorage: found ${orphans.size} orphans, mode=$mode")
                var orphanImported = 0
                for (orphan in orphans) {
                    try {
                        val result = extractHashesFromFile(orphan.fileName, orphan.filePath)
                        val first = result.first
                        val essidFromFile = first?.essid?.takeIf { it.isNotBlank() }
                            ?: parseEssidFromFileName(orphan.fileName)
                        val bssidFromFile = first?.macAp?.takeIf { it.isNotBlank() }
                            ?: parseBssidFromFileName(orphan.fileName)
                        val keyverFromFile = first?.keyver
                        val hasValidData =
                            result.handshakeCount > 0 || (result.pmkidCount > 0 && result.hashPmkid != null)

                        storageManager.saveHandshakeMetadata(
                            HandshakeItem(
                                filePath = orphan.filePath,
                                fileName = orphan.fileName,
                                bssid = bssidFromFile,
                                essid = essidFromFile,
                                fileSize = result.fileSize,
                                lastModified = System.currentTimeMillis(),
                                hash22000 = result.hash22000,
                                hashPmkid = result.hashPmkid,
                                originalFormat = orphan.fileName.substringAfterLast('.'),
                                handshakeCount = result.handshakeCount,
                                eapolCount = result.eapolCount,
                                pmkidCount = result.pmkidCount,
                                hashDedupMd5 = result.hashDedupMd5,
                                isValid = if (hasValidData) null else false,
                                keyver = keyverFromFile
                            )
                        )
                        tryCrackInBackground(
                            HandshakeItem(
                                filePath = orphan.filePath,
                                fileName = orphan.fileName,
                                bssid = bssidFromFile,
                                essid = essidFromFile,
                                fileSize = result.fileSize,
                                lastModified = System.currentTimeMillis(),
                                hash22000 = result.hash22000,
                                hashPmkid = result.hashPmkid,
                                originalFormat = orphan.fileName.substringAfterLast('.'),
                                handshakeCount = result.handshakeCount,
                                eapolCount = result.eapolCount,
                                pmkidCount = result.pmkidCount,
                                hashDedupMd5 = result.hashDedupMd5,
                                isValid = if (hasValidData) null else false,
                                keyver = keyverFromFile
                            )
                        )
                        orphanImported++
                    } catch (_: Exception) {
                    }
                }
                if (orphanImported > 0) {
                    Log.d(tag, "refreshStorage: imported $orphanImported orphans")
                }


                if (mode == RefreshMode.REPARSE_ALL) {
                    val items = storageManager.listHandshakes().filter { it.fileExists }
                    var reparseCount = 0
                    for (item in items) {
                        try {
                            val nativeHashes = captureRunner.readCapBytesAndParse(item.filePath)

                            val hcxHashes = if (ChrootCapabilities.hasChrootTools(getApplication())) {
                                try {
                                    val chrootPath = storageManager.ensureChrootCopy(item.filePath)
                                    if (chrootPath != null) {
                                        val raw = captureRunner.getHcxpcapngtoolOutput(chrootPath)
                                        raw.lines()
                                            .mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                                    } else emptyList()
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            } else emptyList()

                            val allHashes = (nativeHashes + hcxHashes).distinctBy { it.dedupKey() }
                            if (allHashes.isEmpty()) {
                                storageManager.updateHandshakeValid(item.fileName, false)
                                continue
                            }

                            val hash22000 = allHashes.map { it.to22000Line() }.distinct()
                                .joinToString("\n").takeIf { it.isNotBlank() }
                            val validPmkidHashes =
                                allHashes.filter { it.type == HandshakeType.PMKID && !it.isUselessPmkid }
                            val hashPmkid =
                                validPmkidHashes.map { it.pmkidOrMic }.filter { it.length == 32 }
                                    .joinToString("\n").takeIf { it.isNotBlank() }
                            val hash16800 = allHashes.firstOrNull { it.type == HandshakeType.PMKID }
                                ?.let { HandshakeParser.convertTo16800(it) }

                            if (hash22000 != null) storageManager.updateHandshakeHash22000(
                                item.fileName,
                                hash22000
                            )
                            if (hashPmkid != null) storageManager.updateHandshakeHashPmkid(
                                item.fileName,
                                hashPmkid
                            )

                            val firstHash = allHashes.firstOrNull()
                            if (firstHash != null) {
                                storageManager.updateHandshakeEssid(item.fileName, firstHash.essid)
                                storageManager.updateHandshakeBssid(
                                    item.fileName,
                                    firstHash.macAp.uppercase()
                                )
                                storageManager.updateHandshakeKeyver(
                                    item.fileName,
                                    firstHash.keyver
                                )
                                storageManager.updateHandshakeCounts(
                                    item.fileName,
                                    allHashes.count { it.type == HandshakeType.EAPOL },
                                    allHashes.count { it.type == HandshakeType.PMKID },
                                    allHashes.size
                                )
                            }

                            try {
                                val meta = captureRunner.readCapApMetadata(item.filePath)
                                val apsInFile = buildApsInFile(meta)
                                if (apsInFile != null) storageManager.updateHandshakeApsInFile(
                                    item.fileName,
                                    apsInFile
                                )
                                val ap = meta.values.firstOrNull()
                                if (ap != null) {
                                    storageManager.updateHandshakeMetadata(
                                        item.fileName,
                                        clients = if (ap.clients.isNotEmpty()) ap.clients.joinToString(
                                            ","
                                        ) else null,
                                        channel = ap.channel,
                                        akm = ap.akm,
                                        groupCipher = ap.groupCipher,
                                        pairwiseCipher = ap.pairwiseCipher,
                                        rssi = ap.rssi,
                                        eapolM1Count = ap.eapolM1Count,
                                        eapolM2Count = ap.eapolM2Count,
                                        eapolM3Count = ap.eapolM3Count,
                                        eapolM4Count = ap.eapolM4Count,
                                        beaconCount = ap.beaconCount,
                                        assocReqCount = ap.assocReqCount,
                                        authCount = ap.authCount,
                                        probeReqCount = ap.probeReqCount,
                                        hash16800 = hash16800
                                    )
                                }
                            } catch (_: Exception) {
                            }
                            reparseCount++
                            tryCrackInBackground(item)
                        } catch (_: Exception) {
                        }
                    }
                    Log.d(tag, "refreshStorage: re-parsed $reparseCount items")
                }

                loadStorage()
            } catch (e: Exception) {
                Log.e(tag, "refreshStorage failed", e)
                loadStorage()
            }
        }
    }

    private fun buildApsInFile(meta: Map<String, ApMetadata>): String? {
        val lines = meta.entries.map { (bssid, ap) ->
            val essid = ap.essid ?: "?"
            val ch = if (ap.channel != null && ap.channel > 0) "ch=${ap.channel}" else ""
            "$essid ($bssid) $ch"
        }
        return lines.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun parseEssidFromFileName(fileName: String): String? {
        val nameWithoutExt = fileName.substringBeforeLast('.')
        val macPattern = Regex("_[0-9A-Fa-f]{12}$")
        return nameWithoutExt.replace(macPattern, "").replace('_', ' ').takeIf { it.isNotBlank() }
    }

    private fun parseBssidFromFileName(fileName: String): String? {
        val macRaw = Regex("([0-9A-Fa-f]{12})").find(fileName)
        return macRaw?.groupValues?.get(1)?.uppercase()?.chunked(2)?.joinToString(":")
    }

    private fun applyFilters() {
        val query = _searchQuery.value?.lowercase() ?: ""
        val mode = _sortMode.value ?: SortMode.DATE_DESC
        var filtered = allItems

        if (query.isNotBlank()) {
            filtered = filtered.filter {
                it.essid?.lowercase()?.contains(query) == true ||
                        it.bssid?.lowercase()?.contains(query) == true ||
                        it.fileName.lowercase().contains(query)
            }
        }

        filtered = when (mode) {
            SortMode.DATE_DESC -> filtered.sortedByDescending { it.lastModified }
            SortMode.DATE_ASC -> filtered.sortedBy { it.lastModified }
            SortMode.NAME_ASC -> filtered.sortedBy { it.displayName.lowercase() }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.displayName.lowercase() }
        }

        _storageItems.postValue(filtered)
    }

    fun importFromUri(uri: Uri, onResult: (HandshakeImportManager.ImportResult) -> Unit) {
        Log.d(tag, "importFromUri: $uri")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = importManager.importFromUri(uri)
                Log.d(
                    tag,
                    "importFromUri result: success=${result.successCount}, fail=${result.failCount}"
                )
                loadStorage()
                withContext(Dispatchers.Main) { onResult(result) }
            } catch (e: Exception) {
                Log.e(tag, "importFromUri failed", e)
                withContext(Dispatchers.Main) {
                    onResult(
                        HandshakeImportManager.ImportResult(
                            0,
                            1,
                            emptyList(),
                            listOf("Import failed: ${e.message}")
                        )
                    )
                }
            }
        }
    }

    fun importFromUrl(
        url: String,
        isMega: Boolean,
        onResult: (HandshakeImportManager.ImportResult) -> Unit
    ) {
        Log.d(tag, "importFromUrl: url=${url.take(100)}, isMega=$isMega")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = importManager.importFromUrl(url, isMega)
                Log.d(
                    tag,
                    "importFromUrl result: success=${result.successCount}, fail=${result.failCount}"
                )
                loadStorage()
                withContext(Dispatchers.Main) { onResult(result) }
            } catch (e: Exception) {
                Log.e(tag, "importFromUrl failed", e)
                withContext(Dispatchers.Main) {
                    onResult(
                        HandshakeImportManager.ImportResult(
                            0,
                            1,
                            emptyList(),
                            listOf(getApplication<Application>().getString(R.string.hsc_download_failed, e.message))
                        )
                    )
                }
            }
        }
    }

    fun importFromText(text: String, onResult: (HandshakeImportManager.ImportResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = importManager.importFromText(text)
                loadStorage()
                withContext(Dispatchers.Main) { onResult(result) }
            } catch (e: Exception) {
                Log.e(tag, "importFromText failed", e)
                withContext(Dispatchers.Main) {
                    onResult(
                        HandshakeImportManager.ImportResult(
                            0,
                            1,
                            emptyList(),
                            listOf("Import failed: ${e.message}")
                        )
                    )
                }
            }
        }
    }

    fun getWpaSecDictPath(): String? = wpaSecDictManager.getDictPath()

    fun getWpaSecDictInfo(): String {
        val path = wpaSecDictManager.getDictPath()
        if (path != null) {
            return "${wpaSecDictManager.getDictSizeMb()} • $path"
        }
        return getApplication<Application>().getString(R.string.hsc_not_downloaded)
    }

    fun downloadWpaSecDict(onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = wpaSecDictManager.downloadIfNeeded()
            withContext(Dispatchers.Main) { onResult(path) }
        }
    }

    fun clearStorageCrackResult() {
        _storageCrackResult.value = null
    }

    fun clearStorageHcxpcapngtoolResult() {
        _hcxpcapngtoolResult.value = null
    }

    fun deleteHandshakeFileOnly(item: HandshakeItem) {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.deleteHandshake(item.filePath)
            loadStorage()
        }
    }

    fun deleteHandshake(item: HandshakeItem) {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.deleteHandshakeMetaOnly(item.fileName)
            storageManager.deleteHandshake(item.filePath)
            loadStorage()
        }
    }

    fun deleteHandshakeEntryOnly(item: HandshakeItem) {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.deleteHandshakeMetaOnly(item.fileName)
            loadStorage()
        }
    }

    private fun parseHcxpcapngtoolOutput(raw: String): HcxpcapngtoolResult {
        val lines = raw.lines()
        val eapolCount = lines.count { it.contains("EAPOL", ignoreCase = true) }
        val pmkidCount = lines.count { it.contains("PMKID", ignoreCase = true) }
        val packetsTotal = lines.firstOrNull { it.contains("packets inside", ignoreCase = true) }
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val duration = lines.firstOrNull { it.contains("duration", ignoreCase = true) }
            ?.let { Regex("""(\d+)s""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val channel = lines.firstOrNull {
            it.contains("BEACON", ignoreCase = true) && it.contains(
                "channel",
                ignoreCase = true
            )
        }
            ?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val hasValidData = lines.any {
            it.contains("WPA01", ignoreCase = true) || it.contains(
                "WPA02",
                ignoreCase = true
            )
        }

        val firstHashLine = lines.firstOrNull {
            it.startsWith("WPA*01\t") || it.startsWith("WPA*02\t") || it.startsWith("WPA*03\t")
        }
        val parsed = firstHashLine?.let { HandshakeHash.parse22000Line(it.trim()) }
        val essid = parsed?.essid.orEmpty()
        val bssid = parsed?.macAp?.uppercase()

        return HcxpcapngtoolResult(
            valid = hasValidData || eapolCount > 0,
            eapolCount = eapolCount,
            pmkidCount = pmkidCount,
            packetsTotal = packetsTotal,
            durationSec = duration,
            essid = essid,
            bssid = bssid ?: "",
            channel = channel,
            rawOutput = raw
        )
    }

    fun verifyStoredHandshake(item: HandshakeItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chrootPath = if (item.fileExists) {
                    storageManager.ensureChrootCopy(item.filePath) ?: run {
                        Log.e(
                            tag,
                            "verifyStoredHandshake: cannot resolve chroot path for ${item.filePath}"
                        )
                        return@launch
                    }
                } else {
                    Log.e(tag, "verifyStoredHandshake: file does not exist ${item.filePath}")
                    return@launch
                }

                val nativeHashes = captureRunner.readCapBytesAndParse(chrootPath)

                val hcxHashes = if (ChrootCapabilities.hasChrootTools(getApplication())) {
                    try {
                        val raw = captureRunner.getHcxpcapngtoolOutput(chrootPath)
                        raw.lines().mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else emptyList()

                val allHashes = (nativeHashes + hcxHashes).distinctBy { it.dedupKey() }

                Log.d(
                    tag,
                    "verifyStoredHandshake: ${item.fileName}: native=${nativeHashes.size} hcx=${hcxHashes.size} merged=${allHashes.size}"
                )
                for (h in allHashes) {
                    Log.d(
                        tag,
                        "  hash: type=${h.type} essid=${h.essid} bssid=${h.macAp} sta=${h.macSta} mp=${h.messagePair} pmkid=${
                            if (h.type == HandshakeType.PMKID) h.pmkidOrMic.take(16) else "—"
                        }"
                    )
                }

                val hash22000Lines = allHashes.map { it.to22000Line() }.distinct()
                val hash22000 = hash22000Lines.joinToString("\n").takeIf { it.isNotBlank() }
                val validPmkidHashes =
                    allHashes.filter { it.type == HandshakeType.PMKID && !it.isUselessPmkid }
                val hashPmkid = validPmkidHashes.map { it.pmkidOrMic }.filter { it.length == 32 }
                    .joinToString("\n").takeIf { it.isNotBlank() }
                val hash16800 =
                    validPmkidHashes.firstOrNull()?.let { HandshakeParser.convertTo16800(it) }

                val hasValid =
                    allHashes.any { it.type == HandshakeType.EAPOL } || validPmkidHashes.isNotEmpty()
                Log.d(
                    tag,
                    "verifyStoredHandshake: saving: has22000=${hash22000 != null} hasPmkid=${hashPmkid != null} has16800=${hash16800 != null} hasValid=$hasValid"
                )

                if (hash22000 != null) {
                    storageManager.updateHandshakeHash22000(item.fileName, hash22000)
                }
                if (hashPmkid != null) {
                    storageManager.updateHandshakeHashPmkid(item.fileName, hashPmkid)
                }
                storageManager.updateHandshakeValid(item.fileName, hasValid)

                val firstHash = allHashes.firstOrNull()
                if (firstHash != null) {
                    storageManager.updateHandshakeEssid(item.fileName, firstHash.essid)
                    storageManager.updateHandshakeBssid(item.fileName, firstHash.macAp.uppercase())
                    storageManager.updateHandshakeKeyver(item.fileName, firstHash.keyver)
                }


                try {
                    val apMetadata = captureRunner.readCapApMetadata(chrootPath)
                    val apsInFile = buildApsInFile(apMetadata)
                    if (apsInFile != null) storageManager.updateHandshakeApsInFile(
                        item.fileName,
                        apsInFile
                    )
                    val targetBssid = firstHash?.macAp?.uppercase()
                    val entry = apMetadata.entries.firstOrNull { (bssid, _) ->
                        bssid.equals(targetBssid, ignoreCase = true)
                    }
                    if (entry != null) {
                        val meta = entry.value
                        val clientsJson =
                            if (meta.clients.isNotEmpty()) meta.clients.joinToString(",") else null
                        storageManager.updateHandshakeMetadata(
                            item.fileName,
                            clients = clientsJson, channel = meta.channel,
                            akm = meta.akm, groupCipher = meta.groupCipher,
                            pairwiseCipher = meta.pairwiseCipher, rssi = meta.rssi,
                            eapolM1Count = meta.eapolM1Count, eapolM2Count = meta.eapolM2Count,
                            eapolM3Count = meta.eapolM3Count, eapolM4Count = meta.eapolM4Count,
                            beaconCount = meta.beaconCount, assocReqCount = meta.assocReqCount,
                            authCount = meta.authCount, probeReqCount = meta.probeReqCount,
                            hash16800 = hash16800
                        )
                    }
                } catch (_: Exception) {
                }


                val resultHashes = allHashes.map { it.to22000Line() }.distinct()
                _hcxpcapngtoolResult.postValue(
                    HcxpcapngtoolResult(
                        valid = hasValid,
                        eapolCount = allHashes.count { it.type == HandshakeType.EAPOL },
                        pmkidCount = allHashes.count { it.type == HandshakeType.PMKID },
                        packetsTotal = 0,
                        durationSec = 0,
                        essid = firstHash?.essid ?: "",
                        bssid = firstHash?.macAp?.uppercase() ?: "",
                        channel = 0,
                        rawOutput = resultHashes.joinToString("\n")
                    )
                )

                loadStorage()
            } catch (e: Exception) {
                Log.e(tag, "Verify failed for stored handshake", e)
            }
        }
    }

    fun crackStoredHandshake(item: HandshakeItem, wordlistPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chrootPath = storageManager.ensureChrootCopy(item.filePath) ?: run {
                    Log.e(tag, "crackStoredHandshake: cannot resolve chroot path")
                    return@launch
                }
                val password = captureRunner.crackWithWordlist(chrootPath, wordlistPath) { line ->
                    Log.d(tag, "[storage-crack] $line")
                }
                if (password != null) {
                    item.bssid?.let { storageManager.saveCrackedPassword(it, password) }
                    storageManager.updateHandshakeCracked(item.fileName, password)
                    item.bssid?.let { savePasswordToLocalDb(it, item.essid, password) }
                    _storageCrackResult.postValue(item.filePath to password)
                    loadStorage()
                }
            } catch (e: Exception) {
                Log.e(tag, "Crack failed for stored handshake", e)
            }
        }
    }

    fun crackWithSinglePassword(item: HandshakeItem, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chrootPath = storageManager.ensureChrootCopy(item.filePath) ?: return@launch
                val found = captureRunner.crackSinglePassword(chrootPath, password) { line ->
                    Log.d(tag, "[storage-crack-single] $line")
                }
                if (found != null) {
                    item.bssid?.let { storageManager.saveCrackedPassword(it, found) }
                    storageManager.updateHandshakeCracked(item.fileName, found)
                    item.bssid?.let { savePasswordToLocalDb(it, item.essid, found) }
                    _storageCrackResult.postValue(item.filePath to found)
                    loadStorage()
                }
            } catch (e: Exception) {
                Log.e(tag, "Crack single failed", e)
            }
        }
    }

    fun crackWithPasswordList(item: HandshakeItem, passwords: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chrootPath = storageManager.ensureChrootCopy(item.filePath) ?: return@launch
                val found = captureRunner.crackWithPasswords(chrootPath, passwords) { line ->
                    Log.d(tag, "[storage-crack-list] $line")
                }
                if (found != null) {
                    item.bssid?.let { storageManager.saveCrackedPassword(it, found) }
                    storageManager.updateHandshakeCracked(item.fileName, found)
                    item.bssid?.let { savePasswordToLocalDb(it, item.essid, found) }
                    _storageCrackResult.postValue(item.filePath to found)
                    loadStorage()
                }
            } catch (e: Exception) {
                Log.e(tag, "Crack list failed", e)
            }
        }
    }

    fun exportStoredHandshake(item: HandshakeItem, format: String = "hccapx") {
        Log.d(tag, "exportStoredHandshake: format=$format file=${item.fileName}")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chrootPath = storageManager.ensureChrootCopy(item.filePath) ?: run {
                    Log.e(tag, "exportStoredHandshake: chroot path not found for ${item.filePath}")
                    return@launch
                }
                val outputBase = chrootPath.removeSuffix(".cap").removeSuffix(".pcap")
                val success = when (format) {
                    "hccapx" -> captureRunner.exportToHccapx(chrootPath, outputBase) { line ->
                        Log.d(tag, "[storage-export-hccapx] $line")
                    }

                    "22000" -> captureRunner.exportTo22000(
                        chrootPath,
                        "$outputBase.22000"
                    ) { line ->
                        Log.d(tag, "[storage-export-22000] $line")
                    }

                    else -> false
                }
                Log.d(tag, "exportStoredHandshake: format=$format success=$success")
            } catch (e: Exception) {
                Log.e(tag, "Export failed for stored handshake", e)
            }
        }
    }

    private val tempShareDir: String
        get() {
            val dir = File(getApplication<Application>().cacheDir, "handshake_share")
            dir.mkdirs()
            return dir.absolutePath
        }

    fun exportAndGetTempFile(format: String, item: HandshakeItem, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                cleanupOldShareFiles()
                val jvmTempDir = tempShareDir
                File(jvmTempDir).mkdirs()
                val baseName = item.fileName.substringBeforeLast('.')


                if (format == "22000" && item.hash22000 != null) {
                    val jvmDest = "$jvmTempDir/$baseName.22000"
                    File(jvmDest).writeText(item.hash22000)
                    withContext(Dispatchers.Main) { onResult(jvmDest) }
                    return@launch
                }
                if (format == "pmkid" && item.hashPmkid != null) {
                    val jvmDest = "$jvmTempDir/${baseName}_pmkid.txt"
                    File(jvmDest).writeText(item.hashPmkid)
                    withContext(Dispatchers.Main) { onResult(jvmDest) }
                    return@launch
                }
                if (format == "16800" && item.hash16800 != null) {
                    val jvmDest = "$jvmTempDir/${baseName}_16800.txt"
                    File(jvmDest).writeText(item.hash16800)
                    withContext(Dispatchers.Main) { onResult(jvmDest) }
                    return@launch
                }


                val hashText = item.hash22000
                val hashes = if (hashText != null) {
                    HandshakeHash.extractAllFromText(hashText)
                } else {
                    try {
                        captureRunner.readCapBytesAndParse(item.filePath)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (hashes != null && hashes.isNotEmpty()) {
                    val lines =
                        hashes.mapNotNull { it.to22000Line().takeIf { l -> l.isNotBlank() } }
                    val hashTextForConv =
                        if (hashText != null) hashText else lines.joinToString("\n")

                    when (format) {
                        "hccapx" -> {
                            if (hashTextForConv.isNotBlank()) {
                                val bytes =
                                    HandshakeParser.convert22000ToHccapx(hashTextForConv.lines())
                                if (bytes.isNotEmpty()) {
                                    val jvmDest = "$jvmTempDir/$baseName.hccapx"
                                    File(jvmDest).writeBytes(bytes)
                                    withContext(Dispatchers.Main) { onResult(jvmDest) }
                                    return@launch
                                }
                            }
                        }

                        "hccap" -> {
                            if (hashTextForConv.isNotBlank()) {
                                val bytes =
                                    HandshakeParser.convert22000ToHccap(hashTextForConv.lines())
                                if (bytes.isNotEmpty()) {
                                    val jvmDest = "$jvmTempDir/$baseName.hccap"
                                    File(jvmDest).writeBytes(bytes)
                                    withContext(Dispatchers.Main) { onResult(jvmDest) }
                                    return@launch
                                }
                            }
                        }

                        "22000" -> {
                            if (hashTextForConv.isNotBlank()) {
                                val jvmDest = "$jvmTempDir/$baseName.22000"
                                File(jvmDest).writeText(hashTextForConv)
                                withContext(Dispatchers.Main) { onResult(jvmDest) }
                                return@launch
                            }
                        }

                        "pmkid" -> {
                            val pmkidLines = hashes.mapNotNull {
                                if (it.type == HandshakeType.PMKID || it.type == HandshakeType.PMKID_EAPOL)
                                    it.toPmkidLine() else null
                            }
                            if (pmkidLines.isNotEmpty()) {
                                val jvmDest = "$jvmTempDir/${baseName}_pmkid.txt"
                                File(jvmDest).writeText(pmkidLines.joinToString("\n"))
                                withContext(Dispatchers.Main) { onResult(jvmDest) }
                                return@launch
                            }
                        }

                        "16800" -> {
                            val lines16800 = HandshakeParser.convertAllTo16800(hashes)
                            if (lines16800.isNotBlank()) {
                                val jvmDest = "$jvmTempDir/${baseName}_16800.txt"
                                File(jvmDest).writeText(lines16800)
                                withContext(Dispatchers.Main) { onResult(jvmDest) }
                                return@launch
                            }
                        }
                    }
                }


                if (format == "cap" && ChrootCapabilities.hasChrootTools(getApplication())) {

                    val chrootPath = storageManager.ensureChrootCopy(item.filePath)
                    if (chrootPath != null) {
                        val chrootTempDir = "/sdcard/WIFI-Frankenstein/temp"
                        chrootManager.executeInChroot("mkdir -p '$chrootTempDir'")
                        val hashContent = item.hash22000
                            ?: hashes?.mapNotNull { it.to22000Line() }?.joinToString("\n")
                        if (hashContent != null) {
                            val jvmHashFile = "$jvmTempDir/${baseName}_hash.txt"
                            File(jvmHashFile).writeText(hashContent)
                            val chrootHashFile = "$chrootTempDir/${baseName}_hash.txt"
                            val cpHash = com.topjohnwu.superuser.Shell.cmd(
                                "cp '$jvmHashFile' '$chrootHashFile'"
                            ).exec()
                            if (cpHash.isSuccess) {
                                val capOut = "$chrootTempDir/$baseName.cap"
                                chrootManager.executeInChroot(
                                    "hcxhash2cap -o '$capOut' '$chrootHashFile' 2>&1"
                                )
                                val jvmDest = "$jvmTempDir/$baseName.cap"
                                val result = copyFileFromChroot(capOut, jvmDest)
                                if (result != null) {
                                    withContext(Dispatchers.Main) { onResult(result) }
                                    return@launch
                                }
                            }
                        }
                    }
                } else {
                    val chrootPath = storageManager.ensureChrootCopy(item.filePath) ?: run {
                        withContext(Dispatchers.Main) { onResult(null) }
                        return@launch
                    }
                    val chrootTempDir = "/sdcard/WIFI-Frankenstein/temp"
                    chrootManager.executeInChroot("mkdir -p '$chrootTempDir'")
                    var resultPath: String? = null

                    when (format) {
                        "hccapx" -> {
                            val chrootOutBase =
                                chrootPath.removeSuffix(".cap").removeSuffix(".pcap")
                            captureRunner.exportToHccapx(chrootPath, chrootOutBase) { line ->
                                Log.d(tag, "[export hccapx] $line")
                            }
                            val chrootFile = "$chrootOutBase.hccapx"
                            val jvmDest = "$jvmTempDir/$baseName.hccapx"
                            resultPath = copyFileFromChroot(chrootFile, jvmDest)
                        }

                        "22000" -> {
                            val chrootOut = "$chrootTempDir/$baseName.22000"
                            if (ChrootCapabilities.hasChrootTools(getApplication())) {
                                chrootManager.executeInChroot("hcxpcapngtool -o \"$chrootOut\" \"$chrootPath\" 2>&1")
                            }
                            val jvmDest = "$jvmTempDir/$baseName.22000"
                            resultPath = copyFileFromChroot(chrootOut, jvmDest)
                        }

                        "pmkid" -> {
                            val chrootOut = "$chrootTempDir/${baseName}_pmkid.txt"
                            if (ChrootCapabilities.hasChrootTools(getApplication())) {
                                chrootManager.executeInChroot("hcxpcapngtool --pmkid-only -o \"$chrootOut\" \"$chrootPath\" 2>&1")
                            }
                            val jvmDest = "$jvmTempDir/${baseName}_pmkid.txt"
                            resultPath = copyFileFromChroot(chrootOut, jvmDest)
                        }
                    }
                    withContext(Dispatchers.Main) { onResult(resultPath) }
                    return@launch
                }

                withContext(Dispatchers.Main) { onResult(null) }
            } catch (e: Exception) {
                Log.e(tag, "Export failed for format=$format", e)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    private suspend fun resolveHash22000(item: HandshakeItem): String? {
        if (item.hash22000 != null) return item.hash22000
        var hashText: String? = null

        val nativeParsed = captureRunner.readCapBytesAndParse(item.filePath)
        if (nativeParsed.isNotEmpty()) {
            val lines = nativeParsed.map { it.to22000Line() }.distinct()
            hashText = lines.joinToString("\n").takeIf { it.isNotBlank() }
        }

        if (hashText == null && ChrootCapabilities.hasChrootTools(getApplication())) {
            try {
                val chrootPath = storageManager.ensureChrootCopy(item.filePath)
                if (chrootPath != null) {
                    val raw = captureRunner.getHcxpcapngtoolOutput(chrootPath)
                    val lines = raw.lines().filter {
                        it.startsWith("WPA*01") || it.startsWith("WPA*02") || it.startsWith(
                            "WPA*03"
                        )
                    }
                    hashText = lines.joinToString("\n").takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) {
            }
        }

        if (hashText != null) {
            storageManager.updateHandshakeHash22000(item.fileName, hashText)
        }
        return hashText
    }

    fun getHashText(item: HandshakeItem, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hashText = resolveHash22000(item)
                withContext(Dispatchers.Main) { onResult(hashText) }
            } catch (e: Exception) {
                Log.e(tag, "getHashText failed", e)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    fun collectHash22000(items: List<HandshakeItem>, onResult: (List<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val out = mutableListOf<String>()
            for (item in items) {
                try {
                    resolveHash22000(item)?.let { out.add(it) }
                } catch (_: Exception) {
                }
            }
            withContext(Dispatchers.Main) { onResult(out) }
        }
    }

    fun buildBulkShareFile(
        items: List<HandshakeItem>,
        format: String,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val textLines = mutableListOf<String>()
                var binaryBytes: ByteArray? = null

                when (format) {
                    "22000" -> {
                        for (item in items) {
                            try {
                                resolveHash22000(item)?.let { textLines.add(it) }
                            } catch (_: Exception) {
                            }
                        }
                    }

                    "hccapx", "hccap" -> {
                        val lines = mutableListOf<String>()
                        for (item in items) {
                            try {
                                resolveHash22000(item)?.let { lines.add(it) }
                            } catch (_: Exception) {
                            }
                        }
                        if (lines.isNotEmpty()) {
                            binaryBytes = if (format == "hccapx") {
                                HandshakeParser.convert22000ToHccapx(lines)
                            } else {
                                HandshakeParser.convert22000ToHccap(lines)
                            }
                        }
                    }

                    "pmkid" -> {
                        for (item in items) {
                            try {
                                resolveHashPmkid(item)?.let { textLines.add(it) }
                            } catch (_: Exception) {
                            }
                        }
                    }

                    "16800" -> {
                        for (item in items) {
                            try {
                                resolveHash16800(item)?.let { textLines.add(it) }
                            } catch (_: Exception) {
                            }
                        }
                    }

                    else -> {
                        withContext(Dispatchers.Main) { onResult(null) }
                        return@launch
                    }
                }

                if (textLines.isEmpty() && binaryBytes == null) {
                    withContext(Dispatchers.Main) { onResult(null) }
                    return@launch
                }
                cleanupOldShareFiles()
                File(tempShareDir).mkdirs()
                val baseName = if (items.size == 1) {
                    items.first().fileName.substringBeforeLast('.')
                } else {
                    "combined_${System.currentTimeMillis()}"
                }
                val fileName = when (format) {
                    "22000" -> "$baseName.22000"
                    "hccapx" -> "$baseName.hccapx"
                    "hccap" -> "$baseName.hccap"
                    "pmkid" -> "${baseName}_pmkid.txt"
                    "16800" -> "${baseName}_16800.txt"
                    else -> "$baseName.txt"
                }
                val dest = File(tempShareDir, fileName)
                if (binaryBytes != null) {
                    dest.writeBytes(binaryBytes)
                } else {
                    dest.writeText(textLines.joinToString("\n"))
                }
                withContext(Dispatchers.Main) { onResult(dest.absolutePath) }
            } catch (e: Exception) {
                Log.e(tag, "buildBulkShareFile failed format=$format", e)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    fun buildBulkZip(paths: List<String>, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (paths.isEmpty()) {
                    withContext(Dispatchers.Main) { onResult(null) }
                    return@launch
                }
                cleanupOldShareFiles()
                File(tempShareDir).mkdirs()
                val dest = File(tempShareDir, "handshakes_${System.currentTimeMillis()}.zip")
                ZipOutputStream(BufferedOutputStream(FileOutputStream(dest))).use { zos ->
                    for (path in paths) {
                        val src = File(path)
                        if (!src.exists()) continue
                        zos.putNextEntry(ZipEntry(src.name))
                        BufferedInputStream(FileInputStream(src)).use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
                if (dest.length() == 0L) {
                    dest.delete()
                    withContext(Dispatchers.Main) { onResult(null) }
                    return@launch
                }
                withContext(Dispatchers.Main) { onResult(dest.absolutePath) }
            } catch (e: Exception) {
                Log.e(tag, "buildBulkZip failed", e)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    fun shareablePaths(items: List<HandshakeItem>, onResult: (List<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val paths = mutableListOf<String>()
            for (item in items) {
                if (!item.fileExists) continue
                try {
                    resolveAccessiblePath(item.filePath)?.let { paths.add(it) }
                } catch (_: Exception) {
                }
            }
            withContext(Dispatchers.Main) { onResult(paths) }
        }
    }

    fun getHashPmkidText(item: HandshakeItem, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hash = resolveHashPmkid(item)
                withContext(Dispatchers.Main) { onResult(hash) }
            } catch (e: Exception) {
                Log.e(tag, "getHashPmkidText failed", e)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    fun getHash16800Text(item: HandshakeItem, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hash = resolveHash16800(item)
                withContext(Dispatchers.Main) { onResult(hash) }
            } catch (e: Exception) {
                Log.e(tag, "getHash16800Text failed", e)
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    private suspend fun resolveHashPmkid(item: HandshakeItem): String? {
        if (item.hashPmkid != null) return item.hashPmkid
        var hashPmkid: String? = null

        val nativeParsed = captureRunner.readCapBytesAndParse(item.filePath)
        if (nativeParsed.isNotEmpty()) {
            val validPmkids =
                nativeParsed.filter { it.type == HandshakeType.PMKID && !it.isUselessPmkid }
            hashPmkid = validPmkids.map { it.pmkidOrMic }.filter { it.length == 32 }
                .joinToString("\n").takeIf { it.isNotBlank() }
        }

        if (hashPmkid == null && ChrootCapabilities.hasChrootTools(getApplication())) {
            try {
                val chrootPath = storageManager.ensureChrootCopy(item.filePath)
                if (chrootPath != null) {
                    val raw = captureRunner.getHcxpcapngtoolOutput(chrootPath)
                    val parsed =
                        raw.lines().mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                    val validPmkids =
                        parsed.filter { it.type == HandshakeType.PMKID && !it.isUselessPmkid }
                    hashPmkid = validPmkids.map { it.pmkidOrMic }.filter { it.length == 32 }
                        .joinToString("\n").takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) {
            }
        }

        if (hashPmkid != null) {
            storageManager.updateHandshakeHashPmkid(item.fileName, hashPmkid)
        }
        return hashPmkid
    }

    private suspend fun resolveHash16800(item: HandshakeItem): String? {
        if (item.hash16800 != null) return item.hash16800
        var hash16800: String? = null

        val nativeParsed = captureRunner.readCapBytesAndParse(item.filePath)
        if (nativeParsed.isNotEmpty()) {
            hash16800 = HandshakeParser.convertAllTo16800(nativeParsed)
                .takeIf { it.isNotBlank() }
        }

        if (hash16800 == null && ChrootCapabilities.hasChrootTools(getApplication())) {
            try {
                val chrootPath = storageManager.ensureChrootCopy(item.filePath)
                if (chrootPath != null) {
                    val raw = captureRunner.getHcxpcapngtoolOutput(chrootPath)
                    val parsed =
                        raw.lines().mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
                    hash16800 = HandshakeParser.convertAllTo16800(parsed)
                        .takeIf { it.isNotBlank() }
                }
            } catch (_: Exception) {
            }
        }
        return hash16800
    }

    fun bulkDelete(mode: Int, items: List<HandshakeItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            for (item in items) {
                when (mode) {
                    0 -> storageManager.deleteHandshake(item.filePath)
                    1 -> storageManager.deleteHandshakeEntryAndFile(item.filePath)
                }
            }
            loadStorage()
        }
    }

    fun saveLocationAndHash(
        fileName: String,
        hash22000: String?,
        hashPmkid: String?,
        latitude: Double?,
        longitude: Double?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (hash22000 != null) storageManager.updateHandshakeHash22000(fileName, hash22000)
            if (hashPmkid != null) storageManager.updateHandshakeHashPmkid(fileName, hashPmkid)
            if (latitude != null || longitude != null) {
                storageManager.updateHandshakeLocation(fileName, latitude, longitude)
            }
        }
    }


    fun uploadToOnlineHashCrack(
        item: HandshakeItem,
        email: String,
        onResult: (UploadResult) -> Unit
    ) {
        Log.d(tag, "uploadToOnlineHashCrack: file=${item.fileName}")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = getUploadFile(item) ?: run {
                    Log.e(tag, "uploadToOnlineHashCrack: file not found")
                    withContext(Dispatchers.Main) {
                        onResult(
                            UploadResult(
                                false,
                                "File not found"
                            )
                        )
                    }
                    return@launch
                }
                val result = ohcClient.uploadPublic(file, email)
                Log.d(
                    tag,
                    "uploadToOnlineHashCrack: success=${result.success}, accepted=${result.acceptedCount}, skipped=${result.skippedCount}, rejected=${result.rejectedCount}, requestId=${result.requestId}"
                )
                val msg = buildString {
                    append("Accepted: ${result.acceptedCount}")
                    if (result.skippedCount > 0) append(", Skipped: ${result.skippedCount}")
                    if (result.rejectedCount > 0) append(", Rejected: ${result.rejectedCount}")
                    result.reason?.let { append(" ($it)") }
                }
                if (result.success) {
                    storageManager.updateOhcUploadStatus(
                        item.fileName,
                        true,
                        result.requestId,
                        email
                    )
                    storageManager.updateHandshakeHash22000(
                        item.fileName,
                        result.acceptedHashes.joinToString("\n").takeIf { it.isNotBlank() })
                }
                withContext(Dispatchers.Main) {
                    onResult(UploadResult(result.success, msg))
                }
            } catch (e: NetworkException) {
                Log.e(tag, "uploadToOnlineHashCrack network error", e)
                withContext(Dispatchers.Main) {
                    onResult(
                        UploadResult(
                            false,
                            e.message ?: "Network error"
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "uploadToOnlineHashCrack failed", e)
                withContext(Dispatchers.Main) {
                    onResult(
                        UploadResult(
                            false,
                            e.message ?: "Unknown error"
                        )
                    )
                }
            }
        }
    }


    fun uploadHashToOhcPrivate(
        hash22000: String,
        item: HandshakeItem,
        apiKey: String,
        onResult: (UploadResult) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = ohcClient.uploadPrivate(listOf(hash22000), apiKey, algoMode = 22000)
                val msg = buildString {
                    append("Accepted: ${result.acceptedCount}")
                    if (result.skippedCount > 0) append(", Skipped: ${result.skippedCount}")
                    if (result.rejectedCount > 0) append(", Rejected: ${result.rejectedCount}")
                    result.message?.let { append(" - $it") }
                }
                if (result.success) {
                    storageManager.updateOhcUploadStatus(item.fileName, true, result.requestId)
                }
                withContext(Dispatchers.Main) { onResult(UploadResult(result.success, msg)) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(
                        UploadResult(
                            false,
                            e.message ?: "Unknown error"
                        )
                    )
                }
            }
        }
    }


    fun uploadToWpaSec(item: HandshakeItem, apiKey: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hash = item.hash22000 ?: return@launch
                val key = apiKey ?: wpaSecClient.getSavedKey()

                val response = wpaSecClient.uploadHash(hash, key)
                if (response.success) {
                    storageManager.updateWpaSecUploadStatus(item.fileName, true, key)

                    if (response.password != null) {
                        val verified = verifyPasswordAgainstHash(response.password, hash)
                        if (verified) {
                            storageManager.updateHandshakeCracked(item.fileName, response.password)
                            storageManager.updateWpaSecCheckResult(
                                item.fileName, true, true, response.password
                            )
                            item.bssid?.let {
                                savePasswordToLocalDb(
                                    it,
                                    item.essid,
                                    response.password
                                )
                            }
                            _wpaSecResult.postValue(item.fileName to response.password)
                        } else {
                            Log.w(
                                tag,
                                "wpa-sec password verification FAILED for ${item.fileName}: password='${response.password}' doesn't match any PMKID hash"
                            )
                            _wpaSecResult.postValue(item.fileName to "password_mismatch")
                        }
                    } else {
                        checkOnWpaSecSuspend(item)
                    }
                    loadStorage()
                } else {
                    _wpaSecResult.postValue(item.fileName to "__UPLOAD_FAILED__")
                }
            } catch (e: Exception) {
                Log.e(tag, "uploadToWpaSec failed", e)
            }
        }
    }

    private fun verifyPasswordAgainstHash(password: String, hash22000: String): Boolean {
        val lines = hash22000.lines()
        for (line in lines) {
            val parsed = HandshakeHash.parse22000Line(line.trim()) ?: continue
            if (parsed.type == HandshakeType.PMKID && parsed.verifyPassword(password)) {
                return true
            }
        }
        return false
    }

    private suspend fun checkOnWpaSecSuspend(item: HandshakeItem) {
        try {
            val bssid = item.bssid ?: return
            val essid = item.essid ?: return
            val bssidHex = wpaSecClient.bssidToHex(bssid)
            val essidHex = wpaSecClient.essidToHex(essid)
            val found = withContext(Dispatchers.IO) {
                wpaSecClient.checkPasswordByBssidSsid(bssidHex, essidHex)
            }
            storageManager.updateWpaSecCheckResult(item.fileName, true, found, null)
            _wpaSecResult.postValue(
                item.fileName to if (found) "password_known" else "not_found"
            )
            loadStorage()
        } catch (e: Exception) {
            Log.e(tag, "checkOnWpaSecSuspend failed", e)
        }
    }


    fun checkOnWpaSec(item: HandshakeItem) {
        viewModelScope.launch(Dispatchers.IO) {
            checkOnWpaSecSuspend(item)
        }
    }


    fun checkAllOnWpaSec() {
        viewModelScope.launch(Dispatchers.IO) {
            for (item in allItems.filter { it.bssid != null && it.essid != null && it.uploadedToWpaSec }) {
                checkOnWpaSecSuspend(item)
            }
            _wpaSecCheckDone.postValue(true)
        }
    }

    fun clearWpaSecResult() {
        _wpaSecResult.value = null
    }

    fun clearWpaSecCheckDone() {
        _wpaSecCheckDone.value = false
    }

    private suspend fun getUploadFile(item: HandshakeItem): File? = withContext(Dispatchers.IO) {
        val jvmFile = File(item.filePath)
        if (jvmFile.exists() && jvmFile.extension in listOf("cap", "pcap", "pcapng")) {
            return@withContext jvmFile
        }
        val chrootPath = storageManager.ensureChrootCopy(item.filePath) ?: return@withContext null
        val tempFile = File(tempShareDir, item.fileName)
        tempFile.parentFile?.mkdirs()
        val result =
            com.topjohnwu.superuser.Shell.cmd("cp '$chrootPath' '${tempFile.absolutePath}'").exec()
        if (result.isSuccess && tempFile.exists()) return@withContext tempFile
        null
    }

    private suspend fun copyFileFromChroot(chrootPath: String, jvmDest: String): String? =
        withContext(Dispatchers.IO) {
            try {
                File(jvmDest).parentFile?.mkdirs()
                val shell = com.topjohnwu.superuser.Shell.cmd("cp '$chrootPath' '$jvmDest'").exec()
                if (shell.isSuccess && File(jvmDest).exists()) jvmDest else null
            } catch (e: Exception) {
                Log.e(tag, "copyFileFromChroot failed: $chrootPath -> $jvmDest", e)
                null
            }
        }

    fun ensureFileAccessible(filePath: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = resolveAccessiblePath(filePath)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private suspend fun resolveAccessiblePath(filePath: String): String? =
        withContext(Dispatchers.IO) {
            try {
                if (filePath.startsWith("/sdcard/")) {
                    val fileName = File(filePath).name
                    val cacheDir = File(getApplication<Application>().cacheDir, "handshake_share")
                    cacheDir.mkdirs()
                    val cacheFile = File(cacheDir, fileName)
                    val cp =
                        com.topjohnwu.superuser.Shell.cmd("cp '$filePath' '${cacheFile.absolutePath}'")
                            .exec()
                    if (cp.isSuccess && cacheFile.exists()) {
                        cacheFile.absolutePath
                    } else {
                        storageManager.getHandshakeFile(filePath)?.absolutePath
                    }
                } else {
                    filePath
                }
            } catch (e: Exception) {
                Log.e(tag, "ensureFileAccessible failed", e)
                null
            }
        }

    private fun loadDbItemList(): List<DbItem> {
        return try {
            val prefs = getApplication<Application>()
                .getSharedPreferences("db_setup_prefs", Context.MODE_PRIVATE)
            val json = prefs.getString("db_list", null) ?: return emptyList()
            Json.decodeFromString<List<DbItem>>(json)
        } catch (e: Exception) {
            Log.e(tag, "loadDbItemList failed", e)
            emptyList()
        }
    }

    private fun tryCrackInBackground(item: HandshakeItem) {
        if (item.bssid == null || item.crackedPassword != null) return
        val hashText = item.hash22000 ?: item.hashPmkid ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hash = HandshakeHash.parseAny(hashText) ?: return@launch
                val bssid = item.bssid ?: return@launch
                val essid = item.essid
                val dbItems = loadDbItemList()

                val bssidPasswords = mutableSetOf<String>()
                val essidPasswords = mutableSetOf<String>()


                try {
                    val localMatches = LocalAppDbHelper(getApplication())
                        .searchRecordsWithFiltersOptimized(bssid, false, true, false, false)
                    localMatches.mapNotNull { it.wifiPassword?.takeIf { p -> p.isNotBlank() } }
                        .forEach { bssidPasswords.add(it) }
                } catch (e: Exception) {
                    Log.e(tag, "Local DB BSSID search failed", e)
                }

                for (dbItem in dbItems) {
                    try {
                        when (dbItem.dbType) {
                            DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                                val helper = SQLite3WiFiHelper(
                                    getApplication(), Uri.parse(dbItem.path), dbItem.directPath
                                )
                                if (helper.database?.isOpen == true) {
                                    val results = helper.searchNetworksByBSSIDsAsync(listOf(bssid))
                                    results.mapNotNull { r ->
                                        r["WiFiKey"]?.toString()?.takeIf { it.isNotBlank() }
                                    }
                                        .forEach { bssidPasswords.add(it) }
                                }
                            }

                            DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                                val tableName = dbItem.tableName ?: continue
                                val columnMap = dbItem.columnMap ?: continue
                                val pwColumn = columnMap["password"] ?: continue
                                val helper = SQLiteCustomHelper(
                                    getApplication(), Uri.parse(dbItem.path), dbItem.directPath
                                )
                                val results = helper.searchNetworksByBSSIDs(
                                    tableName,
                                    columnMap,
                                    listOf(bssid)
                                )
                                results.values.mapNotNull {
                                    it[pwColumn]?.toString()?.takeIf { p -> p.isNotBlank() }
                                }
                                    .forEach { bssidPasswords.add(it) }
                            }

                            else -> {}
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "DB BSSID search failed for ${dbItem.id}", e)
                    }
                }


                if (essid != null) {
                    try {
                        val localEssidMatches = LocalAppDbHelper(getApplication())
                            .searchRecordsWithFiltersOptimized(essid, true, false, false, false)
                        localEssidMatches
                            .mapNotNull { it.wifiPassword?.takeIf { p -> p.isNotBlank() } }
                            .forEach { essidPasswords.add(it) }
                    } catch (e: Exception) {
                        Log.e(tag, "Local DB ESSID search failed", e)
                    }

                    for (dbItem in dbItems) {
                        try {
                            when (dbItem.dbType) {
                                DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                                    val helper = SQLite3WiFiHelper(
                                        getApplication(), Uri.parse(dbItem.path), dbItem.directPath
                                    )
                                    if (helper.database?.isOpen == true) {
                                        val results =
                                            helper.searchNetworksByESSIDsAsync(listOf(essid))
                                        results
                                            .mapNotNull { r ->
                                                r["WiFiKey"]?.toString()?.takeIf { it.isNotBlank() }
                                            }
                                            .forEach { essidPasswords.add(it) }
                                    }
                                }

                                DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                                    val tableName = dbItem.tableName ?: continue
                                    val columnMap = dbItem.columnMap ?: continue
                                    val pwColumn = columnMap["password"] ?: continue
                                    val helper = SQLiteCustomHelper(
                                        getApplication(), Uri.parse(dbItem.path), dbItem.directPath
                                    )
                                    val results = helper.searchNetworksByESSIDsAsync(
                                        tableName, columnMap, listOf(essid)
                                    )
                                    results
                                        .mapNotNull {
                                            it[pwColumn]?.toString()?.takeIf { p -> p.isNotBlank() }
                                        }
                                        .forEach { essidPasswords.add(it) }
                                }

                                else -> {}
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "DB ESSID search failed for ${dbItem.id}", e)
                        }
                    }
                }


                val candidates = (bssidPasswords.take(10) + essidPasswords.take(10)).distinct()
                for (password in candidates) {
                    val result = WpaCracker.tryPassword(password, hash)
                    if (result.found) {
                        storageManager.updateHandshakeCracked(item.fileName, password)
                        storageManager.saveCrackedPassword(bssid, password)
                        savePasswordToLocalDb(bssid, essid, password)
                        Log.d(
                            tag,
                            "Auto-cracked ${item.fileName} with password from local/3WiFi/custom DB"
                        )
                        break
                    }
                }


                try {
                    val bssidHex = wpaSecClient.bssidToHex(bssid)
                    val essidHex = essid?.let { wpaSecClient.essidToHex(it) }
                    if (essidHex != null && item.crackedPassword == null) {
                        val foundOnWpaSec =
                            wpaSecClient.checkPasswordByBssidSsid(bssidHex, essidHex)
                        storageManager.updateWpaSecCheckResult(
                            item.fileName,
                            true,
                            foundOnWpaSec,
                            null
                        )
                        if (foundOnWpaSec) {
                            Log.d(tag, "Auto-checked ${item.fileName} — password known on wpa-sec")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "wpa-sec check failed for ${item.fileName}", e)
                }
            } catch (e: Exception) {
                Log.e(tag, "tryCrackInBackground failed for ${item.fileName}", e)
            }
        }
    }

    private fun savePasswordToLocalDb(bssid: String, essid: String?, password: String) {
        if (essid.isNullOrBlank()) return
        try {
            val helper = LocalAppDbHelper(getApplication())
            val existing = helper.searchRecordsWithFiltersOptimized(
                bssid, false, true, false, false
            )
            if (existing.none { it.wifiName == essid && it.wifiPassword == password }) {
                helper.addRecord(WifiNetwork(0, essid, bssid, password))
                Log.d(tag, "Added password for $essid ($bssid) to local DB")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to save password to local DB", e)
        }
    }

    override fun onCleared() {
        cleanupTempFiles()
        super.onCleared()
    }

    private fun cleanupTempFiles() {
        cleanupOldShareFiles()
    }

    private fun cleanupOldShareFiles() {
        try {
            val shareDir = File(tempShareDir)
            if (shareDir.exists()) {
                shareDir.listFiles()?.forEach { it.delete() }
            }
        } catch (_: Exception) {
        }
    }
}
