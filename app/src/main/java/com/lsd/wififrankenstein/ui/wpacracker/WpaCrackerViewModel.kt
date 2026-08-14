package com.lsd.wififrankenstein.ui.wpacracker

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.service.WpaCrackService
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeStorageManager
import com.lsd.wififrankenstein.util.BenchmarkProgress
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.HandshakeCaptureRunner
import com.lsd.wififrankenstein.util.HandshakeFormat
import com.lsd.wififrankenstein.util.HandshakeHash
import com.lsd.wififrankenstein.util.HandshakeParser
import com.lsd.wififrankenstein.util.HandshakeType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.OfflineProgress
import com.lsd.wififrankenstein.util.OfflineResult
import com.lsd.wififrankenstein.util.PskOfflineBruteForceRunner
import com.lsd.wififrankenstein.util.WpaBenchmark
import com.lsd.wififrankenstein.util.WpaCracker
import com.lsd.wififrankenstein.util.WpaSecDictManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class CrackEngine { NATIVE, CHROOT_AIRCRACK }

sealed class WpaCrackerState {
    data object Idle : WpaCrackerState()
    data object LoadingHandshake : WpaCrackerState()
    data class Loaded(val hash: HandshakeHash, val fileName: String) : WpaCrackerState()
    data object LoadingWordlist : WpaCrackerState()
    data class Cracking(val progress: OfflineProgress) : WpaCrackerState()
    data class Paused(val progress: OfflineProgress) : WpaCrackerState()
    data class ChrootCracking(val lines: List<String>) : WpaCrackerState()
    data class Done(val result: OfflineResult, val hash: HandshakeHash) : WpaCrackerState()
    data class Error(val message: String) : WpaCrackerState()
}

data class ChrootCrackProgress(
    val speed: String = "",
    val currentPassword: String = "",
    val eta: String = "",
    val attempts: Long = 0,
    val total: Long = 0
) {
    val percent: Double get() = if (total > 0) (attempts.toDouble() / total * 100.0) else -1.0
}

class WpaCrackerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableLiveData<WpaCrackerState>(WpaCrackerState.Idle)
    val state: LiveData<WpaCrackerState> = _state

    private val _hashResult = MutableLiveData<WpaCracker.CrackerResult?>()
    val hashResult: LiveData<WpaCracker.CrackerResult?> = _hashResult

    private val _benchmarkResult = MutableLiveData<WpaBenchmark.Report?>()
    val benchmarkResult: LiveData<WpaBenchmark.Report?> = _benchmarkResult

    private val _benchmarkRunning = MutableLiveData(false)
    val benchmarkRunning: LiveData<Boolean> = _benchmarkRunning

    private val _benchmarkProgress = MutableLiveData<BenchmarkProgress?>()
    val benchmarkProgress: LiveData<BenchmarkProgress?> = _benchmarkProgress

    private val _selectedEngine = MutableLiveData(CrackEngine.NATIVE)

    val selectedEngine: LiveData<CrackEngine> = _selectedEngine

    private val _availableEngines = MutableLiveData<List<CrackEngine>>()
    val availableEngines: LiveData<List<CrackEngine>> = _availableEngines

    private val _handshakeInfo = MutableLiveData(
        application.getString(R.string.wpa_tap_select_handshake)
    )
    val handshakeInfo: LiveData<String> = _handshakeInfo

    private val _wordlistInfo = MutableLiveData(
        application.getString(R.string.wpa_tap_select_wordlist)
    )
    val wordlistInfo: LiveData<String> = _wordlistInfo

    private val _isPreparingWordlist = MutableLiveData(false)
    val isPreparingWordlist: LiveData<Boolean> = _isPreparingWordlist

    private val _consoleLines = MutableLiveData<List<String>>(emptyList())
    val consoleLines: LiveData<List<String>> = _consoleLines

    private val _chrootProgress = MutableLiveData<ChrootCrackProgress?>(null)
    val chrootProgress: LiveData<ChrootCrackProgress?> = _chrootProgress

    private val _chrootStatus = MutableLiveData("")
    val chrootStatus: LiveData<String> = _chrootStatus

    private val _isPaused = MutableLiveData(false)
    val isPaused: LiveData<Boolean> = _isPaused

    private val _isRunningInBackground = MutableLiveData(false)
    val isRunningInBackground: LiveData<Boolean> = _isRunningInBackground

    private val _savedSession = MutableLiveData<CrackSessionData?>()
    val savedSession: LiveData<CrackSessionData?> = _savedSession

    private var currentHash: HandshakeHash? = null
    private var currentFileName: String? = null
    private var wordlistUri: Uri? = null
    private var capChrootPath: String? = null
    private var wordlistChrootPath: String? = null
    private var runner: PskOfflineBruteForceRunner? = null
    private var captureRunner: HandshakeCaptureRunner? = null
    private var nativeCrackJob: kotlinx.coroutines.Job? = null
    private var lastProgress: OfflineProgress? = null

    private val sessionManager = CrackSessionManager(getApplication())
    private var crackReceiver: BroadcastReceiver? = null
    private var receiverRegistered = false

    init {
        checkChrootAvailability()
    }

    private fun registerCrackReceiver() {
        if (receiverRegistered) return
        val app = getApplication<Application>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WpaCrackService.BROADCAST_CRACK_PROGRESS -> {
                        val password =
                            intent.getStringExtra(WpaCrackService.EXTRA_CURRENT_PASSWORD) ?: ""
                        val attempts = intent.getLongExtra(WpaCrackService.EXTRA_ATTEMPTS, 0)
                        val totalLines = intent.getLongExtra(WpaCrackService.EXTRA_TOTAL_LINES, 0)
                        val speed = intent.getDoubleExtra(WpaCrackService.EXTRA_SPEED, 0.0)
                        val offset = intent.getLongExtra(WpaCrackService.EXTRA_OFFSET, 0)
                        val progress =
                            OfflineProgress(password, attempts, totalLines, speed, 0, 0, offset)
                        lastProgress = progress
                        _state.postValue(WpaCrackerState.Cracking(progress))
                    }

                    WpaCrackService.BROADCAST_CRACK_FOUND -> {
                        val password = intent.getStringExtra(WpaCrackService.EXTRA_RESULT_PSK) ?: ""
                        val hash = currentHash ?: return@onReceive
                        val crackResult = WpaCracker.tryPassword(password, hash)
                        _hashResult.postValue(crackResult)
                        persistCrackedResult(password, hash)
                        _state.postValue(
                            WpaCrackerState.Done(
                                OfflineResult(password, 0, 0, 0.0), hash
                            )
                        )
                        _isRunningInBackground.postValue(false)
                        clearCurrentSession()
                    }

                    WpaCrackService.BROADCAST_CRACK_ERROR -> {
                        val msg = intent.getStringExtra(WpaCrackService.EXTRA_ERROR_MESSAGE)
                            ?: "Unknown error"
                        _state.postValue(WpaCrackerState.Error(msg))
                        _isRunningInBackground.postValue(false)
                        clearCurrentSession()
                    }

                    WpaCrackService.BROADCAST_CRACK_PAUSED -> {
                        _isPaused.postValue(true)
                        lastProgress?.let { _state.postValue(WpaCrackerState.Paused(it)) }
                        saveCurrentSession()
                    }

                    WpaCrackService.BROADCAST_CRACK_RESUMED -> {
                        _isPaused.postValue(false)
                        lastProgress?.let { _state.postValue(WpaCrackerState.Cracking(it)) }
                    }

                    WpaCrackService.BROADCAST_CRACK_STOPPED -> {
                        _isPaused.postValue(false)
                        _isRunningInBackground.postValue(false)
                        clearCurrentSession()
                    }

                    WpaCrackService.BROADCAST_CHROOT_LINE -> {
                        val line = intent.getStringExtra(WpaCrackService.EXTRA_PROGRESS_TEXT)
                            ?: ""
                        if (line.isNotBlank()) {
                            val current = _consoleLines.value ?: emptyList()
                            _consoleLines.postValue(current + line)
                            parseChrootLine(line)
                        }
                    }
                }
            }
        }
        crackReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(WpaCrackService.BROADCAST_CRACK_PROGRESS)
            addAction(WpaCrackService.BROADCAST_CRACK_FOUND)
            addAction(WpaCrackService.BROADCAST_CRACK_ERROR)
            addAction(WpaCrackService.BROADCAST_CRACK_PAUSED)
            addAction(WpaCrackService.BROADCAST_CRACK_RESUMED)
            addAction(WpaCrackService.BROADCAST_CRACK_STOPPED)
            addAction(WpaCrackService.BROADCAST_CHROOT_LINE)
        }
        LocalBroadcastManager.getInstance(app).registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun unregisterCrackReceiver() {
        if (!receiverRegistered) return
        val app = getApplication<Application>()
        try {
            crackReceiver?.let { LocalBroadcastManager.getInstance(app).unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        crackReceiver = null
        receiverRegistered = false
    }

    private fun checkChrootAvailability() {
        val ctx = getApplication<Application>()
        val engines = mutableListOf(CrackEngine.NATIVE)
        val hasRoot = ChrootCapabilities.isRootAvailable(ctx)
        val hasChroot = ChrootCapabilities.isAvailable(ctx)
        if (hasRoot) {
            engines.add(CrackEngine.CHROOT_AIRCRACK)
        }
        _availableEngines.value = engines
        _chrootStatus.value = when {
            hasChroot -> getApplication<Application>().getString(R.string.wpa_chroot_available)
            hasRoot -> getApplication<Application>().getString(
                R.string.wpa_chroot_not_installed_use_aircrack
            )

            else -> ""
        }
    }

    fun setEngine(engine: CrackEngine) {
        if (engine == CrackEngine.CHROOT_AIRCRACK && !ChrootCapabilities.isAvailable(getApplication())) {
            return
        }
        _selectedEngine.value = engine
    }

    fun runBenchmark() {
        _benchmarkRunning.value = true
        _benchmarkProgress.value = BenchmarkProgress(
            getApplication<Application>().getString(R.string.wpa_starting)
        )
        _benchmarkResult.value = null
        val benchmark = WpaBenchmark(getApplication())
        viewModelScope.launch {
            try {
                val report = benchmark.runAll { progress ->
                    _benchmarkProgress.postValue(progress)
                }
                _benchmarkResult.value = report
                _benchmarkProgress.value = BenchmarkProgress(
                    getApplication<Application>().getString(R.string.wpa_benchmark_complete),
                    getApplication<Application>().getString(R.string.wpa_benchmark_all_finished),
                    100
                )
            } catch (e: Exception) {
                Log.e("WpaCrackerVM", "Benchmark failed", e)
                _benchmarkProgress.value = BenchmarkProgress(
                    getApplication<Application>().getString(
                        R.string.wpa_benchmark_error,
                        e.message
                    )
                )
            } finally {
                _benchmarkRunning.value = false
            }
        }
    }

    fun clearBenchmarkResult() {
        _benchmarkResult.value = null
        _benchmarkProgress.value = null
    }

    fun loadHandshakeFile(uri: Uri) {
        _state.value = WpaCrackerState.LoadingHandshake
        _handshakeInfo.value = getApplication<Application>().getString(R.string.wpa_loading_handshake)
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val inputStream = app.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    _state.value = WpaCrackerState.Error(
                        getApplication<Application>().getString(R.string.wpa_cannot_open_file)
                    )
                    _handshakeInfo.value =
                        getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
                    return@launch
                }
                val bytes = inputStream.use { it.readBytes() }
                val fileName = uri.lastPathSegment ?: "unknown"

                val tempFile = File(app.cacheDir, "wpa_cracker_handshake_${System.nanoTime()}")
                tempFile.writeBytes(bytes)

                val hash = parseHandshakeFile(tempFile, fileName)
                tempFile.delete()

                if (hash != null) {
                    currentHash = hash.first
                    currentFileName = fileName
                    _handshakeInfo.value = hash.second
                    _state.value = WpaCrackerState.Loaded(hash.first, fileName)
                } else {
                    _state.value = WpaCrackerState.Error(
                        getApplication<Application>().getString(R.string.wpa_no_handshakes_in_file)
                    )
                    _handshakeInfo.value =
                        getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
                }
            } catch (e: Exception) {
                _state.value = WpaCrackerState.Error(
                    getApplication<Application>().getString(
                        R.string.wpa_error_loading_file,
                        e.message
                    )
                )
                _handshakeInfo.value =
                    getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
            }
        }
    }

    fun loadHandshakeFromUrl(url: String, isMega: Boolean) {
        _state.value = WpaCrackerState.LoadingHandshake
        _handshakeInfo.value =
            getApplication<Application>().getString(R.string.wpa_downloading_handshake)
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val result = downloadFile(app, url, isMega, "handshake_dl") ?: run {
                    _state.value = WpaCrackerState.Error(
                        getApplication<Application>().getString(R.string.wpa_download_failed)
                    )
                    _handshakeInfo.value =
                        getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
                    return@launch
                }
                val (tempFile, fileName) = result
                val hash = parseHandshakeFile(tempFile, fileName)
                if (hash != null) {
                    currentHash = hash.first
                    currentFileName = fileName
                    _handshakeInfo.value = hash.second
                    _state.value = WpaCrackerState.Loaded(hash.first, fileName)
                } else {
                    _state.value = WpaCrackerState.Error(
                        getApplication<Application>().getString(
                            R.string.wpa_no_handshakes_downloaded
                        )
                    )
                    _handshakeInfo.value =
                        getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
                }
            } catch (e: Exception) {
                _state.value = WpaCrackerState.Error(
                    getApplication<Application>().getString(R.string.wpa_download_error, e.message)
                )
                _handshakeInfo.value =
                    getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
            }
        }
    }

    fun loadHandshakeFromText(text: String) {
        _state.value = WpaCrackerState.LoadingHandshake
        _handshakeInfo.value = getApplication<Application>().getString(R.string.wpa_parsing_pasted)
        viewModelScope.launch {
            try {
                val hashes = HandshakeParser.parseText(text)
                if (hashes.isEmpty()) {
                    _state.value = WpaCrackerState.Error(
                        getApplication<Application>().getString(R.string.wpa_no_valid_hashes_pasted)
                    )
                    _handshakeInfo.value =
                        getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
                    return@launch
                }
                val hash = hashes.first()
                currentHash = hash
                currentFileName = "pasted"
                val count = hashes.size
                _handshakeInfo.value = getApplication<Application>().getString(
                    R.string.wpa_pasted_hash_essid,
                    hash.essid,
                    count,
                    if (count > 1) "es" else ""
                )
                _state.value = WpaCrackerState.Loaded(hash, "pasted")
            } catch (e: Exception) {
                _state.value = WpaCrackerState.Error(
                    getApplication<Application>().getString(R.string.wpa_error_msg, e.message)
                )
                _handshakeInfo.value =
                    getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
            }
        }
    }

    private suspend fun parseHandshakeFile(
        file: File,
        fileName: String
    ): Pair<HandshakeHash, String>? {
        val format = HandshakeHash.detectFileFormat(file)
        val hashes = when (format) {
            HandshakeFormat.PCAP, HandshakeFormat.PCAPNG -> HandshakeParser.parseFile(file)

            else -> HandshakeParser.parseFile(file)
        }
        if (hashes.isEmpty()) return null
        val hash = hashes.firstOrNull { h ->
            when (h.type) {
                HandshakeType.EAPOL -> h.anonce != null && h.eapol != null
                HandshakeType.PMKID, HandshakeType.PMKID_EAPOL -> h.pmkidOrMic.isNotBlank()
            }
        } ?: hashes.first()

        val capPath = saveCapForChroot(file, fileName, format)

        return hash to buildString {
            when (format) {
                HandshakeFormat.PCAP, HandshakeFormat.PCAPNG -> append(
                    getApplication<Application>().getString(
                        R.string.wpa_info_capture,
                        fileName,
                        hash.essid
                    )
                )

                HandshakeFormat.M22000 -> append(
                    getApplication<Application>().getString(
                        R.string.wpa_info_hash,
                        fileName,
                        hash.essid
                    )
                )

                HandshakeFormat.HCCAPX -> append(
                    getApplication<Application>().getString(
                        R.string.wpa_info_hccapx,
                        fileName,
                        hash.essid
                    )
                )

                HandshakeFormat.HCCAP -> append(
                    getApplication<Application>().getString(
                        R.string.wpa_info_hccap,
                        fileName,
                        hash.essid
                    )
                )

                HandshakeFormat.PMKID -> append(
                    getApplication<Application>().getString(
                        R.string.wpa_info_pmkid,
                        fileName,
                        hash.essid
                    )
                )

                HandshakeFormat.UNKNOWN -> append(
                    getApplication<Application>().getString(
                        R.string.wpa_info_file,
                        fileName,
                        hash.essid
                    )
                )
            }
        }
    }

    private suspend fun saveCapForChroot(
        file: File,
        fileName: String,
        format: HandshakeFormat
    ): String? {
        if (_selectedEngine.value != CrackEngine.CHROOT_AIRCRACK) return null
        if (!ChrootCapabilities.isAvailable(getApplication())) return null
        val app = getApplication<Application>()
        val capDir = File(app.cacheDir, "wpa_cracker_cap")
        capDir.mkdirs()
        val capFile: File = when (format) {
            HandshakeFormat.PCAP, HandshakeFormat.PCAPNG -> file
            else -> {
                val hash = currentHash ?: return null
                val tmp = File(capDir, "${fileName}_${System.nanoTime()}.22000")
                tmp.writeText(hash.to22000Line())
                val capOut = File(capDir, "${fileName}_${System.nanoTime()}.cap")
                val conv = withContext(Dispatchers.IO) {
                    try {
                        val cm = com.lsd.wififrankenstein.util.ChrootManager.get(app)
                        cm.mountChroot()
                        cm.executeInChroot("mkdir -p /sdcard/WIFI-Frankenstein/temp")
                        val chrootPath = "/sdcard/WIFI-Frankenstein/temp/${tmp.name}"
                        cm.executeInChroot("cp '${jvmToChroot(tmp)}' '$chrootPath' 2>/dev/null")
                        val res =
                            cm.executeInChroot("hcxhash2cap -o /sdcard/WIFI-Frankenstein/temp/${capOut.name} '$chrootPath' 2>&1")
                        if (res.isSuccess) {
                            com.topjohnwu.superuser.Shell.cmd("cp '${"/sdcard/WIFI-Frankenstein/temp/${capOut.name}"}' '${capOut.absolutePath}'")
                                .exec()
                            if (capOut.exists()) capOut else null
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
                if (conv != null && conv.exists()) conv else return null
            }
        }
        capChrootPath = chrootize(capFile)
        return capChrootPath
    }

    fun loadHandshakeFromStorage(
        hashes: List<HandshakeHash>,
        selectedIndex: Int = 0,
        storageFileName: String? = null
    ) {
        if (hashes.isEmpty()) {
            _state.value = WpaCrackerState.Error(
                getApplication<Application>().getString(R.string.wpa_no_hashes_storage)
            )
            _handshakeInfo.value =
                getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
            return
        }
        val hash = hashes[selectedIndex.coerceIn(0, hashes.lastIndex)]
        currentHash = hash
        currentFileName = storageFileName
        val count = hashes.size
        val suffix = if (count > 1) {
            getApplication<Application>().getString(R.string.wpa_hashes_suffix, count)
        } else ""
        _handshakeInfo.value = getApplication<Application>().getString(
            R.string.wpa_storage_info,
            hash.essid,
            hash.macAp,
            suffix
        )
        _state.value = WpaCrackerState.Loaded(hash, "storage")
    }

    fun setHandshakeHash(hash: HandshakeHash, fileName: String = "imported") {
        currentHash = hash
        currentFileName = fileName
        _handshakeInfo.value =
            getApplication<Application>().getString(R.string.wpa_imported, hash.essid)
        _state.value = WpaCrackerState.Loaded(hash, fileName)
    }

    fun setHandshakeLine(line: String) {
        val hash = HandshakeHash.parseAny(line)
        if (hash != null) {
            currentHash = hash
            currentFileName = "22000 line"
            _handshakeInfo.value = getApplication<Application>().getString(R.string.wpa_line, hash.essid)
            _state.value = WpaCrackerState.Loaded(hash, "22000 line")
        } else {
            _state.value = WpaCrackerState.Error(
                getApplication<Application>().getString(R.string.wpa_invalid_22000)
            )
            _handshakeInfo.value =
                getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
        }
    }

    fun setWordlistFile(uri: Uri) {
        wordlistUri = uri
        val fileName = uri.lastPathSegment ?: "wordlist.txt"
        _wordlistInfo.value = getApplication<Application>().getString(R.string.wpa_file, fileName)
        if (_selectedEngine.value == CrackEngine.CHROOT_AIRCRACK) {
            viewModelScope.launch {
                copyWordlistToChroot(uri)
            }
        }
        checkSessionMatch()
    }

    fun loadWordlistFromUrl(url: String, isMega: Boolean) {
        _isPreparingWordlist.value = true
        _wordlistInfo.value =
            getApplication<Application>().getString(R.string.wpa_downloading_wordlist)
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val result = downloadFile(app, url, isMega, "wordlist_dl") ?: run {
                    _wordlistInfo.value = getApplication<Application>().getString(
                        R.string.wpa_download_failed_retry
                    )
                    return@launch
                }
                val (tempFile, fileName) = result
                val uri = Uri.fromFile(tempFile)
                wordlistUri = uri
                _wordlistInfo.value = getApplication<Application>().getString(R.string.wpa_url, fileName)
                if (_selectedEngine.value == CrackEngine.CHROOT_AIRCRACK) {
                    copyWordlistToChroot(uri)
                }
                checkSessionMatch()
            } catch (e: Exception) {
                _wordlistInfo.value = getApplication<Application>().getString(
                    R.string.wpa_download_error,
                    e.message
                )
            } finally {
                _isPreparingWordlist.value = false
            }
        }
    }

    fun setWordlistFromPaste(passwords: List<String>) {
        _isPreparingWordlist.value = true
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val tempFile = File(app.cacheDir, "pasted_wordlist_${System.nanoTime()}.txt")
                tempFile.writeText(passwords.joinToString("\n"))
                wordlistUri = Uri.fromFile(tempFile)
                _wordlistInfo.value = getApplication<Application>().getString(
                    R.string.wpa_pasted_passwords,
                    passwords.size
                )
                if (_selectedEngine.value == CrackEngine.CHROOT_AIRCRACK) {
                    copyWordlistToChroot(wordlistUri!!)
                }
                checkSessionMatch()
            } catch (e: Exception) {
                _wordlistInfo.value = getApplication<Application>().getString(
                    R.string.wpa_error_msg,
                    e.message
                )
            } finally {
                _isPreparingWordlist.value = false
            }
        }
    }

    fun useWpaSecDict() {
        _isPreparingWordlist.value = true
        _wordlistInfo.value =
            getApplication<Application>().getString(R.string.wpa_checking_wpasec)
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val manager = WpaSecDictManager(app)
                val path = withContext(Dispatchers.IO) { manager.downloadIfNeeded() }
                if (path != null) {
                    val file = File(path)
                    wordlistUri = Uri.fromFile(file)
                    val mb = if (file.length() > 0) {
                        getApplication<Application>().getString(
                            R.string.wpa_mb_suffix,
                            file.length() / (1024 * 1024)
                        )
                    } else ""
                    _wordlistInfo.value = getApplication<Application>().getString(
                        R.string.wpa_wpasec_dict,
                        mb
                    )
                    if (_selectedEngine.value == CrackEngine.CHROOT_AIRCRACK) {
                        wordlistChrootPath = chrootize(file)
                    }
                    checkSessionMatch()
                } else {
                    val cached = manager.getDictPath()
                    if (cached != null) {
                        wordlistUri = Uri.fromFile(File(cached))
                        _wordlistInfo.value = getApplication<Application>().getString(
                            R.string.wpa_wpasec_dict_cached
                        )
                        if (_selectedEngine.value == CrackEngine.CHROOT_AIRCRACK) {
                            wordlistChrootPath = chrootize(File(cached))
                        }
                        checkSessionMatch()
                    } else {
                        _wordlistInfo.value = getApplication<Application>().getString(
                            R.string.wpa_wpasec_unavailable
                        )
                    }
                }
            } catch (e: Exception) {
                _wordlistInfo.value = getApplication<Application>().getString(
                    R.string.wpa_wpasec_error,
                    e.message
                )
            } finally {
                _isPreparingWordlist.value = false
            }
        }
    }

    private suspend fun copyWordlistToChroot(uri: Uri) {
        if (!ChrootCapabilities.isAvailable(getApplication())) return
        try {
            val app = getApplication<Application>()
            val inputStream = app.contentResolver.openInputStream(uri) ?: return
            val tempFile = File(app.cacheDir, "wl_chroot_${System.nanoTime()}.txt")
            tempFile.outputStream().use { inputStream.copyTo(it) }
            inputStream.close()
            wordlistChrootPath = chrootize(tempFile)
        } catch (e: Exception) {
            Log.e("WpaCrackerVM", "copyWordlistToChroot failed", e)
        }
    }

    private fun checkSessionMatch() {
        val hash = currentHash ?: return
        val uri = wordlistUri ?: return
        val session = sessionManager.getSession(hash.to22000Line(), uri.toString()) ?: return
        _savedSession.value = session
    }

    private fun clearCurrentSession() {
        val hash = currentHash ?: return
        val uri = wordlistUri ?: return
        sessionManager.removeSession(hash.to22000Line(), uri.toString())
    }

    fun trySinglePassword(password: String) {
        val hash = currentHash ?: return
        viewModelScope.launch {
            val result = WpaCracker.tryPassword(password, hash)
            _hashResult.value = result
            if (result.found) {
                persistCrackedResult(password, hash)
                _state.value = WpaCrackerState.Done(
                    OfflineResult(password, 1, 0, 0.0),
                    hash
                )
            } else {
                _state.value = WpaCrackerState.Error(
                    getApplication<Application>().getString(R.string.wpa_password_did_not_match)
                )
            }
        }
    }

    private fun persistCrackedResult(password: String, hash: HandshakeHash) {
        try {
            val app = getApplication<Application>()
            LocalAppDbHelper(app).addRecord(
                WifiNetwork(
                    id = 0,
                    wifiName = hash.essid,
                    macAddress = hash.macAp,
                    wifiPassword = password
                )
            )
            val storageManager = HandshakeStorageManager(app)
            storageManager.saveCrackedPassword(hash.macAp, password)
            val fileName = currentFileName
            if (fileName != null && fileName != "pasted" && fileName != "22000 line" && fileName != "storage") {
                storageManager.updateHandshakeCracked(fileName, password)
            }
        } catch (e: Exception) {
            Log.e("WpaCrackerVM", "Failed to persist cracked result", e)
        }
    }

    fun startCracking() {
        val hash = currentHash
        if (hash != null) {
            if (hash.type == HandshakeType.EAPOL && (hash.anonce.isNullOrBlank() || hash.eapol.isNullOrBlank())) {
                _state.value = WpaCrackerState.Error(
                    getApplication<Application>().getString(R.string.wpa_invalid_handshake_beacon)
                )
                return
            }
            if ((hash.type == HandshakeType.PMKID || hash.type == HandshakeType.PMKID_EAPOL) && hash.pmkidOrMic.isBlank()) {
                _state.value = WpaCrackerState.Error(
                    getApplication<Application>().getString(R.string.wpa_invalid_pmkid)
                )
                return
            }
        }
        when (_selectedEngine.value ?: CrackEngine.NATIVE) {
            CrackEngine.NATIVE -> startNativeCracking()
            CrackEngine.CHROOT_AIRCRACK -> startChrootCracking()
        }
    }

    private fun startNativeCracking() {
        val hash = currentHash ?: return
        val uri = wordlistUri ?: return

        registerCrackReceiver()

        val hashLine = hash.to22000Line()
        WpaCrackService.startCrack(
            getApplication(),
            hashLine,
            uri.toString(),
            offset = 0,
            totalLines = 0
        )
        _hashResult.value = null
        _isPaused.value = false
        _isRunningInBackground.value = true
        _state.value = WpaCrackerState.Cracking(
            OfflineProgress(
                getApplication<Application>().getString(R.string.wpa_starting),
                0,
                0,
                0.0,
                0,
                0
            )
        )
    }

    fun pauseCracking() {
        when (_selectedEngine.value ?: CrackEngine.NATIVE) {
            CrackEngine.NATIVE -> {
                WpaCrackService.pauseCrack(getApplication())
                _isPaused.value = true
                lastProgress?.let { _state.value = WpaCrackerState.Paused(it) }
                saveCurrentSession()
            }

            CrackEngine.CHROOT_AIRCRACK -> {
                WpaCrackService.stopChrootCrack(getApplication())
                _isPaused.value = true
                _state.value = WpaCrackerState.Paused(
                    OfflineProgress("", 0, 0, 0.0, 0, 0)
                )
                saveCurrentSession()
            }
        }
    }

    fun resumeCracking() {
        when (_selectedEngine.value ?: CrackEngine.NATIVE) {
            CrackEngine.NATIVE -> {
                WpaCrackService.resumeCrack(getApplication())
                _isPaused.value = false
                lastProgress?.let { _state.value = WpaCrackerState.Cracking(it) }
            }

            CrackEngine.CHROOT_AIRCRACK -> {
                _isPaused.value = false
                startChrootCracking()
            }
        }
    }

    fun restoreSession(session: CrackSessionData) {
        val hash = HandshakeHash.parseAny(session.handshakeLine)
        if (hash == null) {
            _state.value = WpaCrackerState.Error(
                getApplication<Application>().getString(R.string.wpa_restore_invalid_data)
            )
            sessionManager.removeSession(session.handshakeLine, session.wordlistUri)
            _savedSession.value = null
            return
        }
        if (hash.type == HandshakeType.EAPOL && (hash.anonce.isNullOrBlank() || hash.eapol.isNullOrBlank())) {
            _state.value = WpaCrackerState.Error(
                getApplication<Application>().getString(R.string.wpa_restore_invalid_handshake)
            )
            sessionManager.removeSession(session.handshakeLine, session.wordlistUri)
            _savedSession.value = null
            return
        }
        if ((hash.type == HandshakeType.PMKID || hash.type == HandshakeType.PMKID_EAPOL) && hash.pmkidOrMic.isBlank()) {
            _state.value = WpaCrackerState.Error(
                getApplication<Application>().getString(R.string.wpa_restore_invalid_pmkid)
            )
            sessionManager.removeSession(session.handshakeLine, session.wordlistUri)
            _savedSession.value = null
            return
        }
        currentHash = hash
        wordlistUri = Uri.parse(session.wordlistUri)
        _handshakeInfo.value =
            getApplication<Application>().getString(R.string.wpa_restored, hash.essid)
        _wordlistInfo.value = getApplication<Application>().getString(
            R.string.wpa_resuming_offset,
            session.offset
        )
        _state.value = WpaCrackerState.Loaded(hash, "restored")

        if (session.engineName == "CHROOT_AIRCRACK") {
            _selectedEngine.value = CrackEngine.CHROOT_AIRCRACK
            startChrootCracking()
        } else {
            _selectedEngine.value = CrackEngine.NATIVE
            registerCrackReceiver()
            val hashLine = hash.to22000Line()
            WpaCrackService.startCrack(
                getApplication(),
                hashLine,
                session.wordlistUri,
                offset = session.offset,
                totalLines = session.totalLines
            )
            _hashResult.value = null
            _isPaused.value = false
            _isRunningInBackground.value = true
            _state.value = WpaCrackerState.Cracking(
                OfflineProgress(
                    getApplication<Application>().getString(R.string.wpa_resuming),
                    session.offset,
                    session.totalLines,
                    0.0,
                    0,
                    0,
                    session.offset
                )
            )
        }
    }

    fun dismissSavedSession() {
        val session = _savedSession.value ?: return
        sessionManager.removeSession(session.handshakeLine, session.wordlistUri)
        _savedSession.value = null
    }

    private fun saveCurrentSession() {
        val hash = currentHash ?: return
        val uri = wordlistUri ?: return
        val offset = lastProgress?.offset ?: 0
        val totalLines = lastProgress?.totalPasswords ?: 0

        sessionManager.saveSession(
            CrackSessionData(
                wordlistUri = uri.toString(),
                handshakeLine = hash.to22000Line(),
                offset = offset,
                totalLines = totalLines,
                engineName = (_selectedEngine.value ?: CrackEngine.NATIVE).name,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun startChrootCracking() {
        val hash = currentHash ?: return
        val uri = wordlistUri ?: run {
            _state.value = WpaCrackerState.Error(
                getApplication<Application>().getString(R.string.wpa_no_wordlist_selected)
            )
            return
        }

        registerCrackReceiver()
        _consoleLines.value = emptyList()
        _chrootProgress.value = null
        _hashResult.value = null
        _isRunningInBackground.value = true
        _state.value = WpaCrackerState.ChrootCracking(emptyList())

        WpaCrackService.startChrootCrack(
            getApplication(),
            hash.to22000Line(),
            uri.toString()
        )
    }

    private fun parseChrootLine(line: String) {
        val current = _chrootProgress.value ?: ChrootCrackProgress()
        when {
            line.startsWith("Time left:") -> {
                val rest = line.removePrefix("Time left:").trim()
                val parts = rest.split(Regex("\\s{3,}"))
                val eta = parts.first().trim()
                val pct = parts.getOrNull(1)?.trimEnd('%')?.toDoubleOrNull() ?: -1.0
                _chrootProgress.postValue(current.copy(eta = eta))
            }

            line.startsWith("Current passphrase:") -> {
                val pass = line.removePrefix("Current passphrase:").trim()
                if (pass.isNotBlank()) {
                    _chrootProgress.postValue(current.copy(currentPassword = pass))
                }
            }

            Regex("""\[\d{2}:\d{2}:\d{2}\]\s+\d+/\d+\s+keys tested""").containsMatchIn(line) -> {
                val m =
                    Regex("""\[(\d{2}:\d{2}:\d{2})\]\s+(\d+)/(\d+)\s+keys tested.*\(([^)]+)\)""").find(
                        line
                    )
                if (m != null) {
                    val rawSpeed = m.groupValues[4]
                    val normalizedSpeed = rawSpeed.replace("k/s", "pw/s")
                    _chrootProgress.postValue(
                        current.copy(
                            attempts = m.groupValues[2].toLongOrNull() ?: 0,
                            total = m.groupValues[3].toLongOrNull() ?: 0,
                            speed = normalizedSpeed
                        )
                    )
                }
            }
        }
    }

    fun cancel() {
        when (_selectedEngine.value ?: CrackEngine.NATIVE) {
            CrackEngine.NATIVE -> {
                WpaCrackService.stopCrack(getApplication())
                _isPaused.value = false
                _isRunningInBackground.value = false
            }

            CrackEngine.CHROOT_AIRCRACK -> {
                WpaCrackService.stopChrootCrack(getApplication())
                _isRunningInBackground.value = false
            }
        }
        runner?.cancel()
        runner = null
        clearCurrentSession()
        _savedSession.value = null
    }

    fun reset() {
        cancel()
        unregisterCrackReceiver()
        currentHash = null
        wordlistUri = null
        capChrootPath = null
        wordlistChrootPath = null
        runner = null
        captureRunner = null
        lastProgress = null
        _state.value = WpaCrackerState.Idle
        _hashResult.value = null
        _consoleLines.value = emptyList()
        _chrootProgress.value = null
        _handshakeInfo.value = getApplication<Application>().getString(R.string.wpa_tap_select_handshake)
        _wordlistInfo.value = getApplication<Application>().getString(R.string.wpa_tap_select_wordlist)
        _isPaused.value = false
        _isRunningInBackground.value = false
    }

    override fun onCleared() {
        super.onCleared()
        runner?.cancel()
        captureRunner?.cancel()
        unregisterCrackReceiver()
    }

    private data class DownloadResult(val file: File, val name: String)

    private suspend fun downloadFile(
        app: Application, url: String, isMega: Boolean, prefix: String
    ): DownloadResult? = withContext(Dispatchers.IO) {
        try {
            val tempDir = File(app.cacheDir, "${prefix}_${System.nanoTime()}")
            tempDir.mkdirs()
            val fileName =
                url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() }
                    ?: "download_${System.nanoTime()}"
            val tempFile = File(tempDir, fileName)

            if (isMega) {
                val megaClient = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val megaDownloader =
                    com.lsd.wififrankenstein.network.MegaPublicDownloader(megaClient)
                val resolvedName = megaDownloader.resolveFileName(url) ?: fileName
                val resolvedFile = File(tempDir, resolvedName)
                val result = megaDownloader.download(url, resolvedFile)
                result.getOrNull()?.let { return@withContext DownloadResult(it, resolvedName) }
                return@withContext null
            }

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url)
                .addHeader("User-Agent", "WIFI-Frankenstein/1.1").build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            response.body?.bytes()?.let { tempFile.writeBytes(it) }

            val ext = fileExtension(tempFile)
            if (ext in listOf("zip", "7z", "gz", "tgz")) {
                val extractDir = File(tempDir, "extracted")
                extractDir.mkdirs()
                val extracted =
                    com.lsd.wififrankenstein.util.ArchiveExtractor.extract(tempFile, extractDir)
                val txtFile = extracted.firstOrNull {
                    it.extension.lowercase() in listOf(
                        "txt",
                        "cap",
                        "pcap",
                        "pcapng",
                        "22000"
                    )
                }
                if (txtFile != null) return@withContext DownloadResult(txtFile, txtFile.name)
                if (extracted.isNotEmpty()) return@withContext DownloadResult(
                    extracted.first(),
                    extracted.first().name
                )
            }

            DownloadResult(tempFile, fileName)
        } catch (e: Exception) {
            Log.e("WpaCrackerVM", "downloadFile failed: $prefix", e)
            null
        }
    }

    private fun fileExtension(file: File): String = file.extension.lowercase()

    private fun chrootize(file: File): String {
        val sdcard = android.os.Environment.getExternalStorageDirectory().absolutePath
        return if (file.absolutePath.startsWith(sdcard)) {
            file.absolutePath.replace(sdcard, "/sdcard")
        } else {
            val chrootPath = "/sdcard/WIFI-Frankenstein/temp/${file.name}"
            com.topjohnwu.superuser.Shell.cmd(
                "mkdir -p /sdcard/WIFI-Frankenstein/temp && cp '${file.absolutePath}' '$chrootPath'"
            ).exec()
            chrootPath
        }
    }

    private fun jvmToChroot(file: File): String {
        val sdcard = android.os.Environment.getExternalStorageDirectory().absolutePath
        return if (file.absolutePath.startsWith(sdcard)) {
            file.absolutePath.replace(sdcard, "/sdcard")
        } else {
            "/sdcard/WIFI-Frankenstein/temp/${file.name}"
        }
    }
}
