package com.lsd.wififrankenstein.ui.internetblocking

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.DnsCheckResult
import com.lsd.wififrankenstein.ui.internetblocking.model.DomainCheckResult
import com.lsd.wififrankenstein.ui.internetblocking.model.MainTabResult
import com.lsd.wififrankenstein.ui.internetblocking.model.SweepResult
import com.lsd.wififrankenstein.ui.internetblocking.model.TcpCheckResult
import com.lsd.wififrankenstein.ui.internetblocking.model.TelegramCheckResult
import com.lsd.wififrankenstein.ui.internetblocking.model.YouTubeCheckResult
import com.lsd.wififrankenstein.ui.internetblocking.scanner.BlockDiagnosis
import com.lsd.wififrankenstein.ui.internetblocking.scanner.DnsScanner
import com.lsd.wififrankenstein.ui.internetblocking.scanner.SniBlockDecision
import com.lsd.wififrankenstein.ui.internetblocking.scanner.SniBlockVerdict
import com.lsd.wififrankenstein.ui.internetblocking.scanner.SniScanner
import com.lsd.wififrankenstein.ui.internetblocking.scanner.Tcp16Scanner
import com.lsd.wififrankenstein.ui.internetblocking.scanner.Tcp16Target
import com.lsd.wififrankenstein.ui.internetblocking.scanner.TcpPingScanner
import com.lsd.wififrankenstein.ui.internetblocking.scanner.TcpPingTarget
import com.lsd.wififrankenstein.ui.internetblocking.scanner.TelegramScanner
import com.lsd.wififrankenstein.ui.internetblocking.scanner.TlsScanner
import com.lsd.wififrankenstein.ui.internetblocking.scanner.YouTubeScanner
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray

enum class SniListType {
    BASE, RUSSIA, UKRAINE, CHINA, BELARUS
}

class InternetBlockingViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "InternetBlockingVM"
        private const val MAIN_CHECK_TIMEOUT_MS = 60_000L
        private const val BASELINE_TIMEOUT_MS = 4000L
        private const val MAIN_TCP_TIMEOUT_MS = 4000L

        private val TLS_OK_STATUSES = setOf(
            CheckStatus.Ok,
            CheckStatus.NotBlocked,
            CheckStatus.Redirect
        )

        private val BLOCKED_STATUSES = setOf(
            CheckStatus.Blocked,
            CheckStatus.DnsSpoof,
            CheckStatus.DnsIntercept,
            CheckStatus.FakeIp,
            CheckStatus.FakeNxdomain,
            CheckStatus.FakeEmpty,
            CheckStatus.DohBlocked,
            CheckStatus.SynDrop,
            CheckStatus.TcpRst,
            CheckStatus.TcpAbort,
            CheckStatus.Refused
        )

        private val BASELINE_TARGETS = listOf(
            TcpPingTarget("1.1.1.1", 443, "1.1.1.1:443"),
            TcpPingTarget("8.8.8.8", 53, "8.8.8.8:53"),
            TcpPingTarget("77.88.8.8", 53, "77.88.8.8:53")
        )
    }

    private val _isChecking = MutableLiveData<Boolean>(false)
    val isChecking: LiveData<Boolean> = _isChecking

    private val appContext: Context = application

    private val _dnsResults = MutableLiveData<List<DnsCheckResult>>(emptyList())
    val dnsResults: LiveData<List<DnsCheckResult>> = _dnsResults

    private val _domainResults = MutableLiveData<List<DomainCheckResult>>(emptyList())
    val domainResults: LiveData<List<DomainCheckResult>> = _domainResults

    private val _tcpResults = MutableLiveData<List<TcpCheckResult>>(emptyList())
    val tcpResults: LiveData<List<TcpCheckResult>> = _tcpResults

    private val _sweepResults = MutableLiveData<List<SweepResult>>(emptyList())
    val sweepResults: LiveData<List<SweepResult>> = _sweepResults

    private val _sweepStatus = MutableLiveData<String>("")
    val sweepStatus: LiveData<String> = _sweepStatus

    private val _telegramResult = MutableLiveData<TelegramCheckResult?>(null)
    val telegramResult: LiveData<TelegramCheckResult?> = _telegramResult

    private val _youtubeResult = MutableLiveData<YouTubeCheckResult?>(null)
    val youtubeResult: LiveData<YouTubeCheckResult?> = _youtubeResult

    private val _mainTabResult = MutableLiveData<MainTabResult?>(null)
    val mainTabResult: LiveData<MainTabResult?> = _mainTabResult

    private val _progress = MutableLiveData<Int>(0)
    val progress: LiveData<Int> = _progress

    private val _progressText = MutableLiveData<String>("")
    val progressText: LiveData<String> = _progressText

    private val _consoleLines = MutableLiveData<List<String>>(emptyList())
    val consoleLines: LiveData<List<String>> = _consoleLines

    private var scanJob: Job? = null
    private var lastStubIps: Set<String> = emptySet()
    private var activeCalls = mutableListOf<okhttp3.Call>()
    private val consoleLock = Any()
    private val configManager = ConfigManager(application)
    private var sniListType: SniListType = configManager.getSavedSniListType()

    fun getCurrentSniListType(): SniListType = sniListType

    private val dnsScanner = DnsScanner(appContext)
    private val tlsScanner = TlsScanner(application) { call ->
        synchronized(activeCalls) { activeCalls.add(call) }
    }
    private val tcp16Scanner = Tcp16Scanner()
    private val tcpPingScanner = TcpPingScanner()
    private val telegramScanner = TelegramScanner()
    private val youtubeScanner = YouTubeScanner()
    private val sniScanner = SniScanner()

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        cancelCheck()
        tlsScanner.shutdown()
        tcp16Scanner.shutdown()
        sniScanner.shutdown()
        telegramScanner.shutdown()
        youtubeScanner.shutdown()
    }

    fun setSniListType(type: SniListType) {
        sniListType = type
        configManager.saveSniListType(type)
        addConsole(appContext.getString(R.string.ib_console_sni_list_changed, type.name))
    }

    fun checkDns() {
        Log.d(TAG, "checkDns() called")
        clearActiveCalls()
        scanJob = viewModelScope.launch {
            try {
                ensureActive()
                _isChecking.value = true
                _progress.value = 0
                addConsole(appContext.getString(R.string.ib_console_dns_start))

                val domains = loadDomains()
                Log.d(TAG, "Loaded ${domains.size} domains for DNS check")

                val results = dnsScanner.checkDnsSpoofing(
                    domains = domains,
                    onProgress = { percent, text ->
                        _progress.postValue(percent)
                        _progressText.postValue(text)
                        addConsole(appContext.getString(R.string.ib_console_progress, text, percent))
                    }
                )

                _dnsResults.value = results
                _isChecking.value = false
                _progress.value = 100

                val spoofed = results.count { it.status == CheckStatus.DnsSpoof }
                val ok = results.count { it.status == CheckStatus.Ok }
                val fakeIp = results.count { it.status == CheckStatus.FakeIp }
                val intercepted = results.count { it.status == CheckStatus.DnsIntercept }
                val fakeNxdomain = results.count { it.status == CheckStatus.FakeNxdomain }
                val fakeEmpty = results.count { it.status == CheckStatus.FakeEmpty }
                val dohBlocked = results.count { it.status == CheckStatus.DohBlocked }


                val ipCount = mutableMapOf<String, Int>()
                for (result in results) {
                    for (ip in result.udpIps) {
                        ipCount[ip] = ipCount.getOrElse(ip) { 0 } + 1
                    }
                }
                val stubIps = ipCount.filterValues { it >= 2 }.keys
                lastStubIps = stubIps

                addConsole(appContext.getString(R.string.ib_console_dns_complete, ok, spoofed, intercepted, fakeNxdomain, fakeEmpty, fakeIp, dohBlocked))
                if (stubIps.isNotEmpty()) {
                    addConsole(appContext.getString(R.string.ib_console_stub_ips, stubIps.joinToString(", ")))
                }
                Log.d(TAG, "DNS check complete: $ok OK, $spoofed spoofed out of ${results.size}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "DNS check failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _isChecking.value = false
                addConsole(appContext.getString(R.string.ib_console_dns_error, e.javaClass.simpleName, e.message))
            }
        }
    }

    fun checkDomains() {
        Log.d(TAG, "checkDomains() called")
        clearActiveCalls()
        scanJob = viewModelScope.launch {
            try {
                ensureActive()
                _isChecking.value = true
                _progress.value = 0
                addConsole(appContext.getString(R.string.ib_console_domains_start))

                val domains = loadDomains()
                Log.d(TAG, "Loaded ${domains.size} domains for TLS check")

                val results = tlsScanner.checkDomains(
                    domains = domains,
                    stubIps = lastStubIps,
                    onProgress = { percent, text ->
                        _progress.postValue(percent)
                        _progressText.postValue(text)
                        addConsole(appContext.getString(R.string.ib_console_progress, text, percent))
                    }
                )

                _domainResults.value = results
                _isChecking.value = false
                _progress.value = 100

                val blocked = results.count {
                    val ok13 =
                        it.tls13Status == CheckStatus.Ok || it.tls13Status == CheckStatus.NotBlocked || it.tls13Status == CheckStatus.Redirect
                    val ok12 =
                        it.tls12Status == CheckStatus.Ok || it.tls12Status == CheckStatus.NotBlocked || it.tls12Status == CheckStatus.Redirect
                    !ok13 || !ok12
                }
                addConsole(appContext.getString(R.string.ib_console_domains_complete, blocked, results.size))
                Log.d(TAG, "Domain check complete: $blocked blocked out of ${results.size}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Domain check failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _isChecking.value = false
                addConsole(appContext.getString(R.string.ib_console_domains_error, e.javaClass.simpleName, e.message))
            }
        }
    }

    private fun selectedSniList(): List<String> = when (sniListType) {
        SniListType.BASE -> SniScanner.BASE_SNI_LIST
        SniListType.RUSSIA -> SniScanner.RUSSIA_SNI_LIST
        SniListType.UKRAINE -> SniScanner.UKRAINE_SNI_LIST
        SniListType.CHINA -> SniScanner.CHINA_SNI_LIST
        SniListType.BELARUS -> SniScanner.BELARUS_SNI_LIST
    }

    fun checkTcp16() {
        Log.d(TAG, "checkTcp16() called")
        clearActiveCalls()
        scanJob = viewModelScope.launch {
            try {
                ensureActive()
                _isChecking.value = true
                _progress.value = 0
                addConsole(appContext.getString(R.string.ib_console_tcp16_start))

                val results = runTcp16Check()

                _tcpResults.value = results
                _isChecking.value = false
                _progress.value = 100

                val failed = results.count { it.status != CheckStatus.Ok }
                addConsole(appContext.getString(R.string.ib_console_tcp16_complete, failed, results.size))
                Log.d(TAG, "TCP 16-20KB check complete: $failed failed out of ${results.size}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "TCP 16-20KB check failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _isChecking.value = false
                addConsole(appContext.getString(R.string.ib_console_tcp16_error, e.javaClass.simpleName, e.message))
            }
        }
    }

    private suspend fun runTcp16Check(): List<TcpCheckResult> {
        val targets = loadTcp16Targets()
        Log.d(TAG, "Loaded ${targets.size} targets for TCP 16-20KB check")
        return tcp16Scanner.checkTcp16(targets)
    }

    fun checkSniSweep() {
        Log.d(TAG, "checkSniSweep() called")
        clearActiveCalls()
        scanJob = viewModelScope.launch {
            try {
                ensureActive()
                _isChecking.value = true
                _sweepResults.value = emptyList()
                _sweepStatus.postValue("")
                addConsole(appContext.getString(R.string.ib_console_sni_start, sniListType.name))


                var failedTargets = (_tcpResults.value ?: emptyList())
                    .filter { it.status != CheckStatus.Ok }
                if (failedTargets.isEmpty()) {
                    addConsole(appContext.getString(R.string.ib_console_tcp16_first))
                    _sweepStatus.postValue(appContext.getString(R.string.ib_sweep_status_tcp16_first))
                    updateProgress(10, appContext.getString(R.string.ib_progress_tcp16))
                    val tcpResults = runTcp16Check()
                    _tcpResults.value = tcpResults
                    failedTargets = tcpResults.filter { it.status != CheckStatus.Ok }
                }

                if (failedTargets.isEmpty()) {
                    val msg = appContext.getString(R.string.ib_msg_no_blocked_targets)
                    addConsole("[-] $msg")
                    Log.w(TAG, "checkSniSweep: $msg")
                    _sweepStatus.postValue(msg)
                    updateProgress(100, msg)
                    _isChecking.value = false
                    return@launch
                }

                val sniList = selectedSniList()
                if (sniList.isEmpty()) {
                    val msg = appContext.getString(R.string.ib_msg_sni_empty, sniListType.name)
                    addConsole("[-] $msg")
                    Log.w(TAG, "checkSniSweep: $msg")
                    _sweepStatus.postValue(msg)
                    _isChecking.value = false
                    return@launch
                }

                val startMsg = appContext.getString(R.string.ib_msg_sweeping, failedTargets.size, sniList.size)
                addConsole("[*] $startMsg")
                _sweepStatus.postValue(startMsg)
                updateProgress(15, startMsg)

                val sweepResults = sniScanner.sweepSni(failedTargets, sniList)
                _sweepResults.value = sweepResults

                val workingSni = sweepResults.size
                val endMsg = if (workingSni > 0) {
                    appContext.getString(R.string.ib_msg_found_sni, workingSni)
                } else {
                    appContext.getString(R.string.ib_msg_no_working_sni)
                }
                addConsole(appContext.getString(R.string.ib_console_sni_complete, workingSni))
                _sweepStatus.postValue(endMsg)
                updateProgress(100, endMsg)
                _isChecking.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "SNI sweep failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _isChecking.value = false
                addConsole(appContext.getString(R.string.ib_console_sni_error, e.javaClass.simpleName, e.message))
                _sweepStatus.postValue(appContext.getString(R.string.ib_sweep_status_error, e.message))
            }
        }
    }

    fun checkTelegram() {
        Log.d(TAG, "checkTelegram() called")
        clearActiveCalls()
        scanJob = viewModelScope.launch {
            try {
                ensureActive()
                _isChecking.value = true
                addConsole(appContext.getString(R.string.ib_console_telegram_start))

                val result = telegramScanner.checkTelegram()

                _telegramResult.value = result
                _isChecking.value = false

                val r = result
                addConsole(appContext.getString(R.string.ib_console_telegram_complete, r.status.label(appContext), r.dcReachableCount, r.dcTotal))
                val dcParts = r.dcResults.map { "${it.label}=${if (it.reachable) "+" else "-"}" }
                addConsole(appContext.getString(R.string.ib_console_dc, dcParts.joinToString(" ")))
                if (r.downloadSpeedKbps != null) {
                    addConsole(
                        appContext.getString(
                            R.string.ib_console_download,
                            r.downloadSpeedKbps,
                            r.downloadBytes?.let { "(${formatBytes(it)})" } ?: ""
                        )
                    )
                } else {
                    addConsole(appContext.getString(R.string.ib_console_download_na))
                }
                if (r.uploadSpeedKbps != null) {
                    addConsole(
                        appContext.getString(
                            R.string.ib_console_upload,
                            r.uploadSpeedKbps,
                            r.uploadBytes?.let { "(${formatBytes(it)})" } ?: ""
                        )
                    )
                } else {
                    addConsole(appContext.getString(R.string.ib_console_upload_na))
                }
                addConsole(appContext.getString(R.string.ib_console_duration, r.totalDurationMs / 1000f))
                Log.d(TAG, "Telegram check complete: ${r.status.label()}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Telegram check failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _isChecking.value = false
                addConsole(appContext.getString(R.string.ib_console_telegram_error, e.javaClass.simpleName, e.message))
            }
        }
    }

    fun checkYoutube() {
        Log.d(TAG, "checkYoutube() called")
        clearActiveCalls()
        scanJob = viewModelScope.launch {
            try {
                ensureActive()
                _isChecking.value = true
                addConsole(appContext.getString(R.string.ib_console_youtube_start))

                val result = youtubeScanner.checkYoutube()

                _youtubeResult.value = result
                _isChecking.value = false

                val r = result
                addConsole(appContext.getString(R.string.ib_console_youtube_complete, r.status.label(appContext), r.endpointReachableCount, r.endpointTotal))
                val epParts =
                    r.endpointResults.map { "${it.label}=${if (it.reachable) "+" else "-"}" }
                addConsole(appContext.getString(R.string.ib_console_endpoints, epParts.joinToString(" ")))
                if (r.downloadSpeedKbps != null) {
                    addConsole(
                        appContext.getString(
                            R.string.ib_console_download,
                            r.downloadSpeedKbps,
                            r.downloadBytes?.let { "(${formatBytes(it)})" } ?: ""
                        )
                    )
                } else {
                    addConsole(appContext.getString(R.string.ib_console_download_na))
                }
                addConsole(appContext.getString(R.string.ib_console_duration, r.totalDurationMs / 1000f))
                Log.d(TAG, "YouTube check complete: ${r.status.label()}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "YouTube check failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _isChecking.value = false
                addConsole(appContext.getString(R.string.ib_console_youtube_error, e.javaClass.simpleName, e.message))
            }
        }
    }

    fun cancelCheck() {
        Log.d(TAG, "cancelCheck() called")
        scanJob?.cancel()
        scanJob = null
        synchronized(activeCalls) {
            for (call in activeCalls) {
                try {
                    call.cancel()
                } catch (e: CancellationException) {
                    _isChecking.postValue(false)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cancel HTTP call", e)
                }
            }
            activeCalls.clear()
        }
        _isChecking.value = false
        addConsole(appContext.getString(R.string.ib_console_cancelled))
    }

    fun clearResults() {
        Log.d(TAG, "clearResults() called")
        _dnsResults.value = emptyList()
        _domainResults.value = emptyList()
        _tcpResults.value = emptyList()
        _sweepResults.value = emptyList()
        _sweepStatus.value = ""
        _telegramResult.value = null
        _youtubeResult.value = null
        _mainTabResult.value = null
        _consoleLines.value = emptyList()
        _progress.value = 0
        _progressText.value = ""
    }

    private fun addConsole(line: String) {
        synchronized(consoleLock) {
            val current = _consoleLines.value ?: emptyList()
            _consoleLines.postValue(current + line)
        }
    }

    private fun updateProgress(percent: Int, text: String) {
        _progress.postValue(percent)
        _progressText.postValue(text)
    }

    private fun clearActiveCalls() {
        synchronized(activeCalls) { activeCalls.clear() }
    }

    private fun loadDomains(): List<String> {
        return try {
            val json =
                getApplication<Application>().resources.openRawResource(R.raw.blocking_domains)
                    .bufferedReader().readText()
            val array = JSONArray(json)
            val domains = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val entry = array.getString(i)
                val domain = entry.split('/').first()
                domains.add(domain)
            }
            Log.d(TAG, "Loaded ${domains.size} domains from blocking_domains.json")
            if (domains.isNotEmpty()) domains else ConfigManager.DEFAULT_DOMAINS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to load blocking_domains.json: ${e.javaClass.simpleName}: ${e.message}"
            )
            ConfigManager.DEFAULT_DOMAINS
        }
    }

    private fun loadTcp16Targets(): List<Tcp16Target> {
        return try {
            val json = getApplication<Application>().resources.openRawResource(R.raw.tcp16_targets)
                .bufferedReader().readText()
            val array = JSONArray(json)
            val targets = mutableListOf<Tcp16Target>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val asn = if (obj.has("asn")) obj.getString("asn") else null
                val sni = if (obj.has("sni")) obj.getString("sni") else null
                targets.add(
                    Tcp16Target(
                        id = obj.getString("id"),
                        provider = obj.getString("provider"),
                        ip = obj.getString("ip"),
                        port = obj.getInt("port"),
                        asn = asn,
                        sni = sni
                    )
                )
            }
            Log.d(TAG, "Loaded ${targets.size} targets from tcp16_targets.json")
            targets
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tcp16_targets.json: ${e.javaClass.simpleName}: ${e.message}")
            listOf(
                Tcp16Target("CF-01", "Cloudflare", "172.67.70.222", 443),
                Tcp16Target("HE-01", "Hetzner", "91.98.156.82", 443)
            )
        }
    }

    fun checkMainDomain(domain: String) {
        if (domain.isBlank()) return
        Log.d(TAG, "checkMainDomain() called for $domain")
        scanJob?.cancel()
        synchronized(activeCalls) { activeCalls.clear() }
        scanJob = viewModelScope.launch {
            try {
                _isChecking.postValue(true)
                _mainTabResult.postValue(null)
                _progress.postValue(0)
                _progressText.postValue("")
                val startTime = System.currentTimeMillis()
                addConsole(appContext.getString(R.string.ib_console_checking_domain, domain))

                val result = withTimeout(MAIN_CHECK_TIMEOUT_MS) {
                    checkMainDomainInternal(domain, startTime)
                }

                _isChecking.value = false
                updateProgress(100, appContext.getString(R.string.ib_progress_done))
                val label = result.overallStatus.label(appContext)
                addConsole(appContext.getString(R.string.ib_console_main_complete, label))
                addConsole("[*] ${result.conclusion}")
                Log.d(
                    TAG,
                    "Main check complete for $domain: $label (${result.totalDurationMs}ms)"
                )
                _mainTabResult.value = result
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Main check timed out for $domain after ${MAIN_CHECK_TIMEOUT_MS}ms")
                _isChecking.postValue(false)
                updateProgress(100, appContext.getString(R.string.ib_progress_timed_out))
                addConsole(appContext.getString(R.string.ib_console_main_timeout, MAIN_CHECK_TIMEOUT_MS / 1000))
                _mainTabResult.value = errorResult(
                    domain,
                    appContext.getString(R.string.ib_error_timeout, MAIN_CHECK_TIMEOUT_MS / 1000)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Main check failed for $domain: ${e.javaClass.simpleName}: ${e.message}",
                    e
                )
                _isChecking.postValue(false)
                addConsole(appContext.getString(R.string.ib_console_main_error, e.javaClass.simpleName, e.message))
                _mainTabResult.value = errorResult(domain, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun CoroutineScope.checkMainDomainInternal(
        domain: String,
        startTime: Long
    ): MainTabResult {

        updateProgress(5, appContext.getString(R.string.ib_progress_dns))
        val dnsVerdict = dnsScanner.quickCheckDns(domain)
        ensureActive()
        addConsole(appContext.getString(R.string.ib_console_dns_line, dnsVerdict.status.label(appContext), dnsVerdict.details))
        val dnsStatus = dnsVerdict.status

        val resolvedIp = dnsVerdict.udpIps.firstOrNull() ?: tlsScanner.resolveDomain(domain)
        ensureActive()
        addConsole(appContext.getString(R.string.ib_console_ip_line, resolvedIp ?: appContext.getString(R.string.ib_unresolvable)))


        updateProgress(8, appContext.getString(R.string.ib_progress_baseline))
        val baselineResults = tcpPingScanner.pingTargets(
            BASELINE_TARGETS,
            timeoutMs = BASELINE_TIMEOUT_MS
        )
        val baselineReachable = baselineResults.any { it.reachable }
        addConsole(
            appContext.getString(
                R.string.ib_console_baseline,
                if (baselineReachable) appContext.getString(R.string.ib_status_ok) else appContext.getString(R.string.ib_baseline_unreachable),
                baselineResults.filter { it.reachable }.size,
                baselineResults.size
            )
        )


        updateProgress(12, appContext.getString(R.string.ib_progress_tcp_tls))
        val targetTcpTargets = if (resolvedIp != null) {
            listOf(
                TcpPingTarget(resolvedIp, 443, "$domain:443"),
                TcpPingTarget(resolvedIp, 80, "$domain:80")
            )
        } else emptyList()

        val tcpDeferred = async {
            if (targetTcpTargets.isNotEmpty()) {
                tcpPingScanner.pingTargets(targetTcpTargets, timeoutMs = MAIN_TCP_TIMEOUT_MS)
            } else emptyList()
        }
        val tlsDeferred = async {
            tlsScanner.checkDomainParallel(
                domain = domain,
                stubIps = lastStubIps,
                onProgress = { percent, text ->
                    updateProgress(12 + (percent * 58 / 100), appContext.getString(R.string.ib_progress_tls, text))
                }
            )
        }

        val tcpResults = tcpDeferred.await()
        val domainResult = tlsDeferred.await()
        ensureActive()

        val tcp443 = tcpResults.firstOrNull { it.port == 443 }
        val tcp80 = tcpResults.firstOrNull { it.port == 80 }
        val tcpReachable = tcp443?.reachable ?: false
        val port80Reachable = tcp80?.reachable ?: false
        val tcpLatencyMs = tcp443?.latencyMs

        updateProgress(
            72,
            appContext.getString(
                R.string.ib_progress_tcp_status,
                if (tcpReachable) appContext.getString(R.string.ib_tcp_open) else appContext.getString(R.string.ib_tcp_blocked),
                if (port80Reachable) appContext.getString(R.string.ib_tcp_open) else appContext.getString(R.string.ib_tcp_blocked)
            )
        )

        val tls13Status = domainResult?.tls13Status ?: CheckStatus.Error
        val tls12Status = domainResult?.tls12Status ?: CheckStatus.Error
        val httpStatus = domainResult?.httpStatus ?: CheckStatus.Error



        val isThrottle = listOf(tls13Status, tls12Status, httpStatus).any {
            it == CheckStatus.ReadTimeout
        }
        updateProgress(76, appContext.getString(R.string.ib_progress_sni_diff))
        val sniVerdict = if (tcpReachable && resolvedIp != null && !isThrottle &&
            (tls13Status !in TLS_OK_STATUSES || tls12Status !in TLS_OK_STATUSES)
        ) {
            try {
                val benign = tlsScanner.checkSniDifferential(resolvedIp)
                addConsole(
                    appContext.getString(
                        R.string.ib_console_sni_diff,
                        resolvedIp,
                        benign.status.label(appContext),
                        benign.detail
                    )
                )
                SniBlockDecision.classify(
                    targetReset = true,
                    targetProgressed = false,
                    benignReset = benign.status != CheckStatus.Ok,
                    benignProgressed = benign.status == CheckStatus.Ok
                )
            } catch (e: Exception) {
                Log.w(TAG, "SNI differential failed: ${e.message}")
                SniBlockVerdict.INCONCLUSIVE
            }
        } else {
            SniBlockVerdict.INCONCLUSIVE
        }

        updateProgress(85, appContext.getString(R.string.ib_progress_analyzing))

        val probeStatuses = listOf(tls13Status, tls12Status, httpStatus)
        val tcp16Detected = probeStatuses.any { it == CheckStatus.ReadTimeout }
        val tcp16Status =
            if (tcp16Detected) CheckStatus.ReadTimeout else CheckStatus.NotBlocked
        val tcp16Detail = if (tcp16Detected) domainResult?.details else null

        val statuses = listOf(dnsStatus, tls13Status, tls12Status, httpStatus)
        val overallStatus = when {
            statuses.all { it in TLS_OK_STATUSES } -> CheckStatus.Ok
            statuses.all { it == CheckStatus.Error || it == CheckStatus.Timeout } -> CheckStatus.Error
            statuses.any { it in BLOCKED_STATUSES } -> CheckStatus.Blocked
            else -> CheckStatus.PartiallyBlocked
        }

        val httpStub = domainResult?.httpStub ?: false
        val tcp443Refused = tcp443?.status == CheckStatus.Refused

        val diagnosis = BlockDiagnosis.diagnose(
            context = appContext,
            dnsStatus = dnsStatus,
            tls13Status = tls13Status,
            tls12Status = tls12Status,
            httpStatus = httpStatus,
            tcpReachable = tcpReachable,
            port80Reachable = port80Reachable,
            baselineReachable = baselineReachable,
            sniDifferential = sniVerdict,
            tcp443Refused = tcp443Refused,
            httpStub = httpStub
        )

        val totalDurationMs = System.currentTimeMillis() - startTime

        return MainTabResult(
            domain = domain,
            resolvedIp = resolvedIp,
            udpIps = dnsVerdict.udpIps,
            dohIps = dnsVerdict.dohIps,
            tls13Status = tls13Status,
            tls12Status = tls12Status,
            httpStatus = httpStatus,
            dnsStatus = dnsStatus,
            dnsDetails = dnsVerdict.details,
            tcpReachable = tcpReachable,
            tcpLatencyMs = tcpLatencyMs,
            tcp16Status = tcp16Status,
            tcp16Detail = tcp16Detail,
            overallStatus = overallStatus,
            totalDurationMs = totalDurationMs,
            baselineReachable = baselineReachable,
            port80Reachable = port80Reachable,
            sniBlocked = sniVerdict == SniBlockVerdict.SNI_BLOCKED,
            blockStage = diagnosis.blockStage,
            blockMechanism = diagnosis.blockMechanism,
            conclusion = diagnosis.conclusion,
            confidence = diagnosis.confidence,
            httpStub = httpStub,
            tls13Detail = domainResult?.tls13Detail,
            tls12Detail = domainResult?.tls12Detail,
            httpDetail = domainResult?.httpDetail,
            tls13Trace = domainResult?.tls13Trace ?: emptyList(),
            tls12Trace = domainResult?.tls12Trace ?: emptyList(),
            httpTrace = domainResult?.httpTrace ?: emptyList()
        )
    }

    private fun errorResult(domain: String, message: String): MainTabResult = MainTabResult(
        domain = domain,
        resolvedIp = null,
        udpIps = emptyList(),
        dohIps = emptyList(),
        tls13Status = CheckStatus.Error,
        tls12Status = CheckStatus.Error,
        httpStatus = CheckStatus.Error,
        dnsStatus = CheckStatus.Error,
        dnsDetails = message,
        tcpReachable = false,
        tcpLatencyMs = null,
        tcp16Status = CheckStatus.Error,
        tcp16Detail = null,
        overallStatus = CheckStatus.Error,
        totalDurationMs = 0,
        blockStage = "Error",
        blockMechanism = message,
        conclusion = appContext.getString(R.string.ib_error_check, message)
    )

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
