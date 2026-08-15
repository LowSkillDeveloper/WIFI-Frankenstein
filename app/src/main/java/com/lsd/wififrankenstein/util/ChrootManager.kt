package com.lsd.wififrankenstein.util

import android.content.Context
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.data.ChrootInfo
import com.lsd.wififrankenstein.data.RouterScanResult
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private data class Chunk(
    val index: Int,
    val start: Long,
    val end: Long,
    val tempFile: File
)

sealed interface ChrootType {
    object None : ChrootType
    object RootMissing : ChrootType
    object Root : ChrootType
    data class RootWithoutChroot(val rt: RuntimeType) : ChrootType
    data class Rootless(val rt: RuntimeType) : ChrootType
}

enum class Architecture(val label: String, val isArm: Boolean) {
    ARM("armhf (32-bit)", true),
    AARCH64("aarch64 (64-bit)", true),
    X86("x86 (32-bit)", false),
    X86_64("x86_64 (64-bit)", false),
    UNKNOWN("Unknown", false)
}

object ChrootManagerSingleton {
    @Volatile
    private var INSTANCE: ChrootManager? = null
    private val lock = Any()

    fun get(context: Context): ChrootManager {
        val appContext = context.applicationContext
        return INSTANCE ?: synchronized(lock) {
            INSTANCE ?: ChrootManager(appContext).also { INSTANCE = it }
        }
    }

    fun release() {
        synchronized(lock) {
            INSTANCE = null
        }
    }
}

class ChrootManager(private val context: Context) {

    companion object {
        private const val CHROOT_BASE = "/data/local/wififrankenstein"
        private const val CHROOT_PATH = "$CHROOT_BASE/chroot"
        private const val BUSYBOX_PATH = "/data/local/wififrankenstein/tools/busybox"
        private const val BUSYBOX_LEGACY = "/data/data/com.lsd.wififrankenstein/files/busybox"
        private const val VERSION_FILE_PATH = "$CHROOT_BASE/chroot_version.txt"
        private const val CHROOT_INFO_URL =
            "https://github.com/LowSkillDeveloper/WIFI-Frankenstein/raw/refs/heads/service/Chroot.json"
        private const val TAG = "ChrootManager"

        private fun rootFileExists(path: String): Boolean {
            return try {
                Shell.cmd("test -f '$path'").exec().isSuccess
            } catch (_: Exception) {
                false
            }
        }

        private fun rootDirExists(path: String): Boolean {
            return try {
                Shell.cmd("test -d '$path'").exec().isSuccess
            } catch (_: Exception) {
                false
            }
        }

        private fun rootCanExecute(path: String): Boolean {
            return try {
                Shell.cmd("test -x '$path'").exec().isSuccess
            } catch (_: Exception) {
                false
            }
        }

        private fun verifyBusyboxApplets(): String? {
            Log.d(TAG, "Verifying busybox applets...")
            try {
                val versionOut =
                    Shell.cmd("$BUSYBOX_PATH --help 2>&1 | $BUSYBOX_PATH head -5").exec()
                Log.d(TAG, "=== BUSYBOX VERSION ===")
                versionOut.out.forEach { Log.d(TAG, "  $it") }
                Log.d(TAG, "=== END BUSYBOX VERSION ===")

                val appletList =
                    Shell.cmd("$BUSYBOX_PATH --list 2>&1 | $BUSYBOX_PATH tr ' ' '\\n' | $BUSYBOX_PATH grep -E '^(mkdir|tar|chroot|chmod|chown|mount|umount|rm|ls|cp)$'")
                        .exec()
                Log.d(TAG, "Critical applets found: ${appletList.out.joinToString(", ")}")
                if (appletList.out.size < 9) {
                    Log.w(
                        TAG,
                        "Some critical applets may be missing: ${appletList.out.joinToString(", ")}"
                    )
                }

                val testDir = "/data/local/tmp/.busybox_test"
                val mkdirTest =
                    Shell.cmd("$BUSYBOX_PATH mkdir -p $testDir && $BUSYBOX_PATH rmdir $testDir")
                        .exec()
                if (!mkdirTest.isSuccess) {
                    Log.e(
                        TAG,
                        "Busybox mkdir test FAILED (exit=${mkdirTest.code}) — busybox may be broken or wrong arch"
                    )
                    return "❌ Busybox: binary broken or incompatible (wrong architecture)"
                }
                val tarTest = Shell.cmd("$BUSYBOX_PATH tar --version 2>&1").exec()
                if (!tarTest.isSuccess || tarTest.out.none { it.contains("tar") || it.contains("BusyBox") }) {
                    Log.w(
                        TAG,
                        "Busybox tar test: exit=${tarTest.code}, out=${tarTest.out.firstOrNull()}"
                    )
                }
                val chrootTest =
                    Shell.cmd("$BUSYBOX_PATH chroot --help 2>&1 | $BUSYBOX_PATH head -1").exec()
                Log.d(TAG, "Busybox chroot test: ${chrootTest.out.firstOrNull() ?: "no output"}")

                val chrootSyscallTest = testChrootSyscall()
                Log.d(TAG, "Chroot syscall test: $chrootSyscallTest")
                if (!chrootSyscallTest) {
                    return "❌ Kernel: chroot() syscall blocked — this ROM/kernel does not support chroot"
                }

                Log.d(TAG, "Busybox applet verification PASSED")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Busybox verification failed", e)
                return "❌ Busybox verification error: ${e.message}"
            }
        }

        private fun testMinimalChroot(baseDir: String): Pair<Boolean, String> {
            val testRoot = "$baseDir/.minimal_root"
            return try {
                val setup = Shell.cmd(
                    "$BUSYBOX_LEGACY mkdir -p $testRoot/bin",
                    "$BUSYBOX_LEGACY cp $BUSYBOX_LEGACY $testRoot/bin/busybox"
                ).exec()
                if (!setup.isSuccess) return false to "setup failed at $baseDir"

                val testBin = if (checkSystemChroot()) "/system/bin/chroot" else "$BUSYBOX_LEGACY chroot"
                val testCmd = "$testBin $testRoot /bin/busybox true 2>&1"
                val result = Shell.cmd(testCmd).exec()
                val ok = result.isSuccess && result.code == 0

                Shell.cmd("$BUSYBOX_LEGACY rm -rf $testRoot 2>/dev/null").exec()

                if (ok) ok to "chroot OK at $baseDir"
                else ok to "chroot FAILED at $baseDir (exit=${result.code})"
            } catch (e: Exception) {
                false to "exception at $baseDir: ${e.message}"
            }
        }

        private fun testChrootSyscall(): Boolean {
            Log.d(TAG, "Testing chroot syscall from multiple locations...")
            val locations = listOf(
                "/data/local/tmp",
                "/data/local",
                "/data/data/com.lsd.wififrankenstein/files",
                "/cache",
                "/storage/emulated/0",
                "/data"
            )

            for (location in locations) {
                val (ok, msg) = testMinimalChroot(location)
                Log.d(TAG, "  $msg")
                if (ok) return true
            }

            Log.e(
                TAG,
                "Chroot syscall blocked by kernel — this device/ROM does not support chroot(). Chroot-dependent features (RouterScan, PixieWps, etc.) will not work."
            )
            return false
        }

        @Volatile
        private var _canUseUnshare: Boolean? = null
        private fun checkUnshare(): Boolean {
            if (_canUseUnshare != null) return _canUseUnshare!!
            val result = Shell.cmd("$BUSYBOX_PATH unshare -m true 2>/dev/null").exec()
            _canUseUnshare = result.isSuccess
            Log.d(TAG, "unshare -m available: ${_canUseUnshare}")
            return _canUseUnshare!!
        }

        @Volatile
        private var _canUseSystemChroot: Boolean? = null
        private fun checkSystemChroot(): Boolean {
            if (_canUseSystemChroot != null) return _canUseSystemChroot!!
            val r = Shell.cmd(
                "test -x /system/bin/chroot && " +
                        "mkdir -p /data/local/tmp/.sc_test/bin && " +
                        "$BUSYBOX_LEGACY cp $BUSYBOX_LEGACY /data/local/tmp/.sc_test/bin/busybox && " +
                        "/system/bin/chroot /data/local/tmp/.sc_test /bin/busybox true && " +
                        "rm -rf /data/local/tmp/.sc_test"
            ).exec()
            _canUseSystemChroot = r.isSuccess
            Log.d(TAG, "system chroot available: ${_canUseSystemChroot}")
            return _canUseSystemChroot!!
        }

        private fun chrootBin(): String =
            if (checkSystemChroot()) "/system/bin/chroot" else "$BUSYBOX_PATH chroot"

        @Volatile
        private var BUSYBOX_COPIED = false
        private val busyboxLock = Any()
        private val mountRefCounter = AtomicInteger(0)

        @Volatile
        var isChrootMounted = false
        private val mountLock = Any()
        private val lifecycleLock = Any()
        private const val MOUNT_FAILURE_COOLDOWN_MS = 60000L

        @Volatile
        private var mountFailedAt: Long = 0
        private const val CHROOT_TYPE_CACHE_MS = 30000L
        fun mountFailedResult(): Shell.Result = object : Shell.Result() {
            override fun getCode() = 1
            override fun isSuccess() = false
            override fun getOut(): MutableList<String> = mutableListOf()
            override fun getErr(): MutableList<String> = mutableListOf()
        }

        @Volatile
        private var chrootTypeCache: ChrootType = ChrootType.None

        @Volatile
        private var chrootTypeCacheTime: Long = 0
        private val shellSemaphore = Semaphore(4)

        fun get(context: Context): ChrootManager {
            return ChrootManagerSingleton.get(context)
        }

        fun release() {
            ChrootManagerSingleton.release()
        }

        fun incrementMountRef() = mountRefCounter.incrementAndGet()
        fun decrementMountRef(): Boolean {
            val val1 = mountRefCounter.decrementAndGet()
            return val1 <= 0
        }


        fun isPathMounted(path: String): Boolean {
            return try {
                val mountsFile = File("/proc/mounts")
                if (!mountsFile.exists()) return false
                val content = mountsFile.readText()


                content.split('\n').any { line ->
                    val firstSpace = line.indexOf(' ')
                    if (firstSpace != -1) {
                        val secondSpaceOffset = line.substring(firstSpace + 1).indexOf(' ')
                        if (secondSpaceOffset != -1) {
                            val mp =
                                line.substring(firstSpace + 1, firstSpace + 1 + secondSpaceOffset)
                            mp == path
                        } else false
                    } else false
                }
            } catch (_: Exception) {
                false
            }
        }


        fun cleanupStaleMounts() {
            try {
                val mountsFile = File("/proc/mounts")
                if (!mountsFile.exists()) return
                val content = mountsFile.readText()
                val staleMounts = mutableListOf<String>()
                content.split('\n').distinct().forEach { line ->
                    val firstSpace = line.indexOf(' ')
                    if (firstSpace != -1) {
                        val secondSpaceOffset = line.substring(firstSpace + 1).indexOf(' ')
                        if (secondSpaceOffset != -1) {
                            val mp =
                                line.substring(firstSpace + 1, firstSpace + 1 + secondSpaceOffset)
                            if (mp.startsWith(CHROOT_PATH) && mp != CHROOT_PATH) {
                                staleMounts.add(mp)
                            }
                        }
                    }
                }
                if (staleMounts.isEmpty()) return
                Log.d(TAG, "Found ${staleMounts.size} stale mounts from previous process")
                staleMounts.distinct().forEach { mp ->
                    Shell.cmd("$BUSYBOX_PATH umount -l '$mp' 2>/dev/null || true").exec()
                }
                Log.d(TAG, "Stale mount cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "Stale mount cleanup failed", e)
            }
        }
    }

    private val chrootDir = File(CHROOT_PATH)
    private val httpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    @Volatile
    private var mountCheckTimestamp = 0L
    private val mountCheckCooldownMs = 5000L

    init {
        extractBusyboxFromAssets()
    }

    private fun extractBusyboxFromAssets() {
        try {
            val legacyFile = File(BUSYBOX_LEGACY)
            if (legacyFile.exists() && legacyFile.canExecute()) return
            legacyFile.parentFile?.mkdirs()
            context.assets.open("busybox").use { assetStream ->
                FileOutputStream(legacyFile).use { output ->
                    assetStream.copyTo(output)
                }
            }
            legacyFile.setExecutable(true, false)
            legacyFile.setReadable(true, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract busybox from assets", e)
        }
    }

    private fun copyBusyboxFromAssets() {
        synchronized(busyboxLock) {
            if (BUSYBOX_COPIED) return
            if (rootFileExists(BUSYBOX_PATH) && rootCanExecute(BUSYBOX_PATH)) {
                BUSYBOX_COPIED = true
                return
            }

            try {
                Log.d(TAG, "Deploying busybox from legacy to $BUSYBOX_PATH")
                val deploy = Shell.cmd(
                    "mkdir -p ${File(BUSYBOX_PATH).parent!!}",
                    "$BUSYBOX_LEGACY cp $BUSYBOX_LEGACY $BUSYBOX_PATH",
                    "chmod 755 $BUSYBOX_PATH"
                ).exec()

                if (!deploy.isSuccess) {
                    Log.w(TAG, "Deploy to $BUSYBOX_PATH failed")
                    BUSYBOX_COPIED = false
                } else {
                    File(BUSYBOX_LEGACY).delete()
                    BUSYBOX_COPIED = true
                    Log.d(TAG, "Busybox ready at $BUSYBOX_PATH")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deploy busybox", e)
            }
        }
    }

    fun getArchitecture(): Architecture {
        val abis = android.os.Build.SUPPORTED_ABIS
        return when {
            abis.any { it.startsWith("arm64") } -> Architecture.AARCH64
            abis.any { it.startsWith("armeabi") } -> Architecture.ARM
            abis.any { it.startsWith("x86_64") } -> Architecture.X86_64
            abis.any { it.startsWith("x86") } -> Architecture.X86
            else -> Architecture.UNKNOWN
        }
    }

    fun isArmArchitecture(): Boolean = getArchitecture().isArm

    fun isAarch64(): Boolean {
        return android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }
    }

    suspend fun getChrootInfo(): ChrootInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(CHROOT_INFO_URL)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonString = response.body?.string() ?: return@withContext null
                Json.decodeFromString(ChrootInfo.serializer(), jsonString)
            } else {
                Log.e(TAG, "Failed to fetch chroot info: HTTP ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch chroot info", e)
            null
        }
    }

    suspend fun getFileSize(url: String): Long = withContext(Dispatchers.IO) {
        try {
            val headRequest = Request.Builder().head().url(url).build()
            val response = httpClient.newCall(headRequest).execute()
            if (response.isSuccessful && response.body != null) {
                val size = response.body!!.contentLength()
                Log.d(TAG, "File size from HEAD $url: $size bytes")
                size
            } else {
                Log.w(TAG, "HEAD request failed for $url: HTTP ${response.code}")
                0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get file size for $url", e)
            0L
        }
    }

    suspend fun downloadAndInstall(
        onProgress: (Int) -> Unit,
        onStatusUpdate: (String) -> Unit,
        onCancelled: (() -> Boolean)? = null,
        onDiagnosticUpdate: ((name: String, icon: String, result: String, fullOutput: String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== downloadAndInstall START ===")
        try {
            onStatusUpdate(context.getString(R.string.chroot_status_fetching_info))
            onProgress(5)

            val chrootInfo = getChrootInfo()
            if (chrootInfo == null) {
                Log.e(TAG, "Failed to get chroot info")
                onStatusUpdate(context.getString(R.string.chroot_status_fetch_failed))
                return@withContext false
            }
            Log.d(TAG, "Chroot info: version=${chrootInfo.version}")

            val archive = if (isAarch64()) chrootInfo.aarch64 else chrootInfo.armhf
            val archLabel = getArchitecture().label

            onStatusUpdate(context.getString(R.string.chroot_status_preparing_env, archLabel))
            onProgress(10)

            copyBusyboxFromAssets()
            onStatusUpdate(context.getString(R.string.chroot_status_perm_diagnostics))
            val diag = ChrootDiagnostics(BUSYBOX_PATH, CHROOT_PATH)
            val diagResults = diag.runDiagnostic { result ->
                val icon = when {
                    result.name == "system_chroot" && result.output.contains("NOT_FOUND") -> " ?"
                    result.name == "kernel_chroot_config" && result.output.contains("CONFIG_UNKNOWN") -> " ?"
                    result.name == "seccomp_status" && result.output.contains("N/A") -> " ?"
                    result.name == "chroot_sysctl" && result.output.contains("N/A") -> " ?"
                    !result.success -> " ✗"
                    result.name == "selinux_status" && result.output.trim() == "Enforcing" -> " !"
                    result.name == "knox_indicators" && result.output.contains("KNOX") -> " !"
                    result.name == "busybox_linkage" && result.output.contains("DYNAMIC") -> " !"
                    result.name == "magiskpolicy" && !result.success -> " !"
                    else -> " ✓"
                }
                val short = buildDiagnosticResultText(result)
                onDiagnosticUpdate?.invoke(result.description, icon, short, result.output)
            }

            val selinuxStage = diagResults.find { it.name == "selinux_status" }
            onStatusUpdate(context.getString(R.string.chroot_status_selinux, selinuxStage?.output?.trim() ?: context.getString(R.string.unknown)))

            val contextStage = diagResults.find { it.name == "context" }
            val ctxLine = contextStage?.output?.lineSequence()?.firstOrNull()?.trim() ?: "unknown"
            onStatusUpdate(context.getString(R.string.chroot_status_context, ctxLine))

            val rootStage = diagResults.find { it.name == "root" }
            onStatusUpdate(context.getString(R.string.chroot_status_root, if (rootStage?.success == true) "OK" else "FAIL"))

            val allAvc = diagResults.flatMap { it.avcEntries }
            val chrootSyscallOk =
                diagResults.any { it.name.startsWith("chroot_syscall") && it.success }
            val busyboxExecAvc = diagResults
                .find { it.name == "busybox_execute" }
                ?.avcEntries.orEmpty()
                .filter { it.tclass == "file" && it.permissions.contains("execute") }
            val chrootExecAvc = diagResults
                .find { it.name == "chroot_execute" }
                ?.avcEntries.orEmpty()
                .filter {
                    it.tclass == "file" && (it.permissions.contains("execute") || it.permissions.contains(
                        "execute_no_trans"
                    ))
                }

            val mcsProblem =
                busyboxExecAvc.any { it.tcontext.contains("app_data_file") } && chrootExecAvc.isEmpty()
            val domainTransition = chrootExecAvc.any { it.permissions.contains("execute_no_trans") }
            val execDirs = diag.findExecDirectories(diagResults)

            if (allAvc.isNotEmpty()) {
                allAvc.forEach { avc ->
                    onStatusUpdate(context.getString(R.string.chroot_status_avc, avc.toReadable()))
                }
            }

            if (mcsProblem) {
                onStatusUpdate(context.getString(R.string.chroot_status_mcs_busybox))
            }
            if (domainTransition) {
                val rules = chrootExecAvc.mapNotNull { it.toMagiskRule() }.distinct()
                onStatusUpdate(context.getString(R.string.chroot_status_domain_transition, rules.firstOrNull() ?: "N/A"))
            }

            val noexecDirs = execDirs.filter { !it.second }
            if (noexecDirs.isNotEmpty()) {
                noexecDirs.forEach { (path, _) ->
                    onStatusUpdate(context.getString(R.string.chroot_status_noexec, path))
                }
                val execDirsAvail = execDirs.filter { it.second }
                if (execDirsAvail.isNotEmpty()) {
                    onStatusUpdate(context.getString(R.string.chroot_status_exec_available, execDirsAvail.first().first))
                }
            }

            val chrootStage = diagResults.find { it.name == "chroot_syscall" }
            val chrootExit126 = chrootStage?.exitCode == 126

            val systemChrootStage = diagResults.find { it.name == "system_chroot" }
            val systemChrootWorks = systemChrootStage?.output?.contains("SYS_CHROOT_OK") == true

            val linkageStage = diagResults.find { it.name == "busybox_linkage" }
            val isDynamicBusybox = linkageStage?.output?.contains("DYNAMIC") == true

            val linkerStage = diagResults.find { it.name == "linker_chroot" }
            val linkerWorks = linkerStage?.success == true

            val knoxStage = diagResults.find { it.name == "knox_indicators" }
            val isKnox = knoxStage?.output?.contains("KNOX") == true

            val seccompStage = diagResults.find { it.name == "seccomp_status" }
            val seccompMode2 = seccompStage?.output?.contains("2") == true

            val kernelCfgStage = diagResults.find { it.name == "kernel_chroot_config" }
            val hasChrootConfig = kernelCfgStage?.output?.contains("CONFIG_CHROOT=y") == true

            val sysctlStage = diagResults.find { it.name == "chroot_sysctl" }
            val chrootDisabled = sysctlStage?.output?.trim() == "0"

            val magiskStage = diagResults.find { it.name == "magiskpolicy" }
            val hasMagiskPolicy = magiskStage?.success == true && magiskStage.output.isNotBlank()

            var failReason = ""
            if (!chrootSyscallOk && !systemChrootWorks) {
                failReason = when {
                    isDynamicBusybox && linkerWorks -> {
                        onStatusUpdate(context.getString(R.string.chroot_status_busybox_dynamic))
                        onStatusUpdate(context.getString(R.string.chroot_status_busybox_solution))
                        "dynamic_busybox"
                    }

                    allAvc.isNotEmpty() && hasMagiskPolicy -> {
                        onStatusUpdate(context.getString(R.string.chroot_status_selinux_blocks, allAvc.size))
                        diag.applyMagiskRules(diagResults)
                        onStatusUpdate(context.getString(R.string.chroot_status_rules_applied))
                        "selinux_fixed"
                    }

                    isKnox && chrootExit126 -> {
                        onStatusUpdate(context.getString(R.string.chroot_status_knox_blocks))
                        onStatusUpdate(context.getString(R.string.chroot_status_knox_kernel))
                        onStatusUpdate(context.getString(R.string.chroot_status_knox_solution))
                        "knox_block"
                    }

                    seccompMode2 && chrootExit126 -> {
                        onStatusUpdate(context.getString(R.string.chroot_status_seccomp_blocks))
                        onStatusUpdate(context.getString(R.string.chroot_status_seccomp_try))
                        "seccomp_block"
                    }

                    kernelCfgStage != null && kernelCfgStage.output != "CONFIG_UNKNOWN" && !hasChrootConfig -> {
                        onStatusUpdate(context.getString(R.string.chroot_status_no_config_chroot))
                        onStatusUpdate(context.getString(R.string.chroot_status_rom_no_chroot))
                        "no_kernel_config"
                    }

                    chrootDisabled -> {
                        onStatusUpdate(context.getString(R.string.chroot_status_sysctl_disabled))
                        "sysctl_disabled"
                    }

                    allAvc.isNotEmpty() && !hasMagiskPolicy -> {
                        val rules = allAvc.mapNotNull { it.toMagiskRule() }.distinct()
                        onStatusUpdate(context.getString(R.string.chroot_status_selinux_no_magisk, rules.size))
                        rules.take(3)
                            .forEach { onStatusUpdate(context.getString(R.string.chroot_status_allow_line, it.removePrefix("allow "))) }
                        onStatusUpdate(context.getString(R.string.chroot_status_apply_magisk))
                        "selinux_no_magisk"
                    }

                    chrootExit126 -> {
                        val ctx =
                            contextStage?.output?.lineSequence()?.firstOrNull()?.trim() ?: "unknown"
                        onStatusUpdate(context.getString(R.string.chroot_status_silent_126))
                        onStatusUpdate(context.getString(R.string.chroot_status_context_line, ctx))
                        onStatusUpdate(context.getString(R.string.chroot_status_possible_causes))
                        "silent_denial"
                    }

                    else -> {
                        onStatusUpdate(context.getString(R.string.chroot_status_test_failed_unknown))
                        "unknown"
                    }
                }
                if (failReason != "selinux_fixed") {
                    val problems = mutableListOf<String>()
                    if (!chrootSyscallOk) problems.add("chroot() syscall blocked (exit 126)")
                    if (isKnox) problems.add(
                        "Samsung Knox v${
                            knoxStage?.output?.lineSequence()
                                ?.firstOrNull { it.trim().all { c -> c.isDigit() || c == 'v' } }
                                ?.trim() ?: "?"
                        }"
                    )
                    if (!hasMagiskPolicy) problems.add("magiskpolicy not found")
                    if (isDynamicBusybox) problems.add("busybox is dynamically linked")
                    if (seccompMode2) problems.add("Seccomp filter active")
                    if (chrootDisabled) problems.add("kernel.chroot_enabled=0")
                    if (!hasChrootConfig && kernelCfgStage != null && kernelCfgStage.output != "CONFIG_UNKNOWN") problems.add(
                        "kernel missing CONFIG_CHROOT"
                    )
                    if (chrootExit126 && problems.isEmpty()) problems.add("chroot blocked silently (unknown cause)")

                    val summary = problems.joinToString("\n")
                    onStatusUpdate(context.getString(R.string.chroot_status_problems, summary))
                    onStatusUpdate(context.getString(R.string.chroot_status_cannot_proceed))
                    onDiagnosticUpdate?.invoke("Problems", " ✗", summary, summary)
                    return@withContext false
                }
            }
            if (systemChrootWorks) {
                onStatusUpdate(context.getString(R.string.chroot_status_system_binary_fallback))
            }

            val verifyResult = verifyBusyboxApplets()
            if (verifyResult != null) {
                Log.e(TAG, "Busybox verification failed: $verifyResult")
                onStatusUpdate(verifyResult)
                return@withContext false
            }

            if (rootDirExists(CHROOT_PATH)) {
                Log.d(TAG, "Old chroot exists, removing...")
                onStatusUpdate(context.getString(R.string.chroot_status_removing_old))
                unmountChroot()
                Shell.cmd("$BUSYBOX_PATH rm -rf $CHROOT_PATH").exec()
                Log.d(TAG, "Old chroot removed")
            }

            Shell.cmd("$BUSYBOX_PATH mkdir -p $CHROOT_PATH").exec()
            restoreOwnership(CHROOT_PATH)
            Log.d(TAG, "Created chroot dir: ${rootDirExists(CHROOT_PATH)}")
            onProgress(15)

            val tempFile = File(context.cacheDir, archive.filename)
            Log.d(TAG, "Temp file path: ${tempFile.absolutePath}")

            onStatusUpdate(context.getString(R.string.chroot_status_downloading, archive.filename))
            Log.d(TAG, "Downloading from: ${archive.download_url}")
            if (!downloadFile(
                    archive.download_url,
                    tempFile,
                    archive.size,
                    onProgress,
                    onCancelled
                )
            ) {
                Log.e(TAG, "Download failed")
                onStatusUpdate(context.getString(R.string.chroot_status_download_failed))
                return@withContext false
            }
            Log.d(TAG, "Download complete. File size: ${tempFile.length()} bytes")

            onStatusUpdate(context.getString(R.string.chroot_status_extracting))
            Log.d(TAG, "Starting extraction...")
            extractTarGz(tempFile, chrootDir, onProgress, onCancelled)
            Log.d(TAG, "Extraction complete")

            onStatusUpdate(context.getString(R.string.chroot_status_setting_up))
            setupChroot()
            onProgress(85)

            onStatusUpdate(context.getString(R.string.chroot_status_saving_version))
            saveVersion(chrootInfo.version)
            onProgress(90)

            restoreOwnership(CHROOT_PATH)

            Shell.cmd("$BUSYBOX_PATH chown -R 0:0 '$CHROOT_PATH/etc/sudo.conf' '$CHROOT_PATH/etc/sudoers' '$CHROOT_PATH/etc/sudoers.d' 2>/dev/null || true")
                .exec()
            Shell.cmd("$BUSYBOX_PATH chmod 440 '$CHROOT_PATH/etc/sudoers' 2>/dev/null || true")
                .exec()

            onStatusUpdate(context.getString(R.string.chroot_status_testing))
            val isValid = testChroot()
            onProgress(95)

            tempFile.delete()
            Log.d(TAG, "Temp file deleted")

            if (isValid) {
                Log.d(TAG, "=== downloadAndInstall SUCCESS ===")
                onStatusUpdate(context.getString(R.string.chroot_status_installation_completed))
                onProgress(100)
                true
            } else {
                Log.e(TAG, "=== downloadAndInstall FAILED: chroot test failed ===")
                onStatusUpdate(context.getString(R.string.chroot_status_test_failed))
                cleanupFailedInstall()
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Installation failed", e)
            onStatusUpdate(context.getString(R.string.chroot_status_installation_failed, e.message))
            cleanupFailedInstall()
            false
        }
    }

    private fun supportsRangeRequests(url: String): Boolean {
        return try {
            val headRequest = Request.Builder().head().url(url).build()
            val response = httpClient.newCall(headRequest).execute()
            response.use {
                val acceptRanges = it.headers("Accept-Ranges").any { it.contains("bytes") }
                if (!acceptRanges) {
                    Log.d(TAG, "Server does not support range requests")
                }
                acceptRanges
            }
        } catch (e: Exception) {
            Log.d(TAG, "Range request check failed: ${e.message}")
            false
        }
    }

    private fun getFileSizeViaRange(url: String): Long {
        return try {
            val rangeRequest = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .build()
            val response = httpClient.newCall(rangeRequest).execute()
            if (response.isSuccessful && response.code == 206) {
                val contentRange = response.headers("Content-Range").firstOrNull()
                if (contentRange != null) {
                    val totalSize = contentRange.substringAfterLast('/').toLongOrNull()
                    if (totalSize != null && totalSize > 0) {
                        Log.d(TAG, "File size from Content-Range: $totalSize bytes")
                        response.close()
                        totalSize
                    } else {
                        Log.w(TAG, "Content-Range header invalid: $contentRange")
                        response.close()
                        0L
                    }
                } else {
                    Log.w(TAG, "No Content-Range header in 206 response")
                    response.close()
                    0L
                }
            } else {
                Log.w(TAG, "Range request failed: HTTP ${response.code}")
                response.close()
                0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Range request failed: ${e.message}")
            0L
        }
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        totalSize: Long,
        onProgress: (Int) -> Unit,
        onCancelled: (() -> Boolean)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading: $url")

            var actualSize = totalSize
            if (actualSize <= 0) {
                Log.d(TAG, "No size provided, trying Range request")
                actualSize = getFileSizeViaRange(url)
                if (actualSize <= 0) {
                    Log.w(TAG, "Could not determine file size, using 0")
                }
            }

            val supportsRanges = supportsRangeRequests(url)
            Log.d(TAG, "Range requests supported: $supportsRanges, file size: $actualSize bytes")

            if (supportsRanges && actualSize > 0) {
                downloadFileParallel(url, destination, actualSize, onProgress, onCancelled)
            } else {
                downloadFileSingleThreaded(url, destination, actualSize, onProgress, onCancelled)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Download cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            false
        }
    }

    private suspend fun downloadFileParallel(
        url: String,
        destination: File,
        totalSize: Long,
        onProgress: (Int) -> Unit,
        onCancelled: (() -> Boolean)? = null
    ): Boolean {
        val CHUNK_COUNT = 4
        val CHUNK_SIZE = totalSize / CHUNK_COUNT

        destination.parentFile?.mkdirs()


        val chunks = (0 until CHUNK_COUNT).map { i ->
            val start = i * CHUNK_SIZE
            val end = if (i == CHUNK_COUNT - 1) totalSize - 1 else (i + 1) * CHUNK_SIZE - 1
            val tempFile =
                File(context.cacheDir, "chroot_chunk_${i}_${System.currentTimeMillis()}.tmp")
            Chunk(i, start, end, tempFile)
        }


        val downloaded = AtomicLong(0)
        var allSuccess = true

        try {
            coroutineScope {
                val deferreds = chunks.map { chunk ->
                    async(Dispatchers.IO) {
                        if (onCancelled?.invoke() == true) {
                            Log.d(TAG, "Parallel download cancelled by user")
                            throw CancellationException("Download cancelled")
                        }

                        try {
                            val request = Request.Builder()
                                .url(url)
                                .header("Range", "bytes=${chunk.start}-${chunk.end}")
                                .build()

                            val response = httpClient.newCall(request).execute()
                            if (!response.isSuccessful) {
                                Log.e(TAG, "Chunk ${chunk.index} failed: HTTP ${response.code}")
                                return@async false
                            }

                            val responseBody = response.body
                            if (responseBody == null) {
                                Log.e(TAG, "Chunk ${chunk.index} has no body")
                                return@async false
                            }

                            var lastReportedProgress = -1
                            responseBody.byteStream().use { input ->
                                FileOutputStream(chunk.tempFile).use { output ->
                                    val buffer = ByteArray(65536)
                                    var chunkBytesRead = 0L
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        if (onCancelled?.invoke() == true) {
                                            Log.d(TAG, "Chunk ${chunk.index} cancelled by user")
                                            throw CancellationException("Download cancelled")
                                        }
                                        output.write(buffer, 0, bytesRead)
                                        chunkBytesRead += bytesRead
                                        val totalSoFar = downloaded.get() + chunkBytesRead
                                        val newPct = (totalSoFar * 30L / totalSize).toInt()
                                        if (newPct != lastReportedProgress) {
                                            lastReportedProgress = newPct
                                            onProgress(15 + newPct)
                                        }
                                    }
                                }
                            }

                            val chunkDownloaded = chunk.tempFile.length()
                            val expectedSize = chunk.end - chunk.start + 1
                            if (chunkDownloaded != expectedSize) {
                                Log.e(
                                    TAG,
                                    "Chunk ${chunk.index} size mismatch: ${chunkDownloaded} vs ${expectedSize}"
                                )
                                return@async false
                            }

                            downloaded.addAndGet(chunkDownloaded)
                            return@async true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Chunk ${chunk.index} download failed: ${e.message}")
                            return@async false
                        }
                    }
                }

                for (result in deferreds.map { it.await() }) {
                    if (!result) {
                        allSuccess = false
                        break
                    }
                }
            }

            if (!allSuccess) {
                Log.e(TAG, "Parallel download failed, cleaning up")
                chunks.forEach { it.tempFile.delete() }
                destination.delete()
                return false
            }


            Log.d(TAG, "Merging ${chunks.size} chunks sequentially")
            FileOutputStream(destination).use { output ->
                for (chunk in chunks) {
                    if (onCancelled?.invoke() == true) {
                        Log.d(TAG, "Merge cancelled by user")
                        chunks.forEach { it.tempFile.delete() }
                        destination.delete()
                        throw CancellationException("Merge cancelled")
                    }
                    chunk.tempFile.inputStream().use { input ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }
            }


            chunks.forEach { it.tempFile.delete() }

            val finalSize = destination.length()
            if (finalSize != totalSize) {
                Log.e(TAG, "Final size mismatch: $finalSize vs $totalSize")
                destination.delete()
                return false
            }

            Log.d(TAG, "Parallel download complete: ${destination.length()} bytes")
            return true
        } catch (e: CancellationException) {
            chunks.forEach { it.tempFile.delete() }
            destination.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Parallel download failed", e)
            chunks.forEach { it.tempFile.delete() }
            destination.delete()
            return false
        }
    }

    private suspend fun downloadFileSingleThreaded(
        url: String,
        destination: File,
        totalSize: Long,
        onProgress: (Int) -> Unit,
        onCancelled: (() -> Boolean)? = null
    ): Boolean {
        destination.parentFile?.mkdirs()

        return try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Single-threaded download failed: HTTP ${response.code}")
                return false
            }

            val body = response.body ?: return false
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(65536)
                    var downloaded = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (onCancelled?.invoke() == true) {
                            Log.d(TAG, "Single-threaded download cancelled by user")
                            throw CancellationException("Download cancelled")
                        }
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            val progressPct = (downloaded * 30L / totalSize).toInt()
                            onProgress(15 + progressPct)
                        }
                    }
                }
            }

            Log.d(TAG, "Single-threaded download complete: ${destination.length()} bytes")
            true
        } catch (e: CancellationException) {
            destination.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Single-threaded download failed", e)
            destination.delete()
            false
        }
    }

    private fun extractTarGz(
        archive: File,
        destination: File,
        onProgress: (Int) -> Unit,
        onCancelled: (() -> Boolean)? = null
    ) {
        Log.d(TAG, "=== extractTarGz START ===")
        Log.d(TAG, "Archive: ${archive.absolutePath}")
        Log.d(TAG, "Archive size: ${archive.length()} bytes")
        Log.d(TAG, "Destination: ${destination.absolutePath}")
        Log.d(TAG, "Busybox: $BUSYBOX_PATH")

        if (!archive.exists()) {
            throw Exception("Archive file not found: ${archive.absolutePath}")
        }

        if (!rootFileExists(BUSYBOX_PATH)) {
            throw Exception("Busybox not found at $BUSYBOX_PATH")
        }

        Log.d(TAG, "Busybox canExecute: ${rootCanExecute(BUSYBOX_PATH)}")

        onProgress(45)

        val tarCmdStr =
            "$BUSYBOX_PATH tar -xzf ${archive.absolutePath} -C ${destination.absolutePath}"
        Log.d(TAG, "tar command: $tarCmdStr")

        val result = Shell.cmd(tarCmdStr).exec()
        val exitCode = result.code
        val mergedOut = result.out.joinToString("\n")

        Log.d(TAG, "tar exitCode: $exitCode")
        if (mergedOut.isNotBlank()) Log.d(TAG, "tar output: $mergedOut")

        if (exitCode != 0) {
            Log.e(TAG, "Extraction failed (exitCode=$exitCode): $mergedOut")
            throw Exception("Extraction failed (exitCode=$exitCode): $mergedOut")
        }

        val lsResult =
            Shell.cmd("$BUSYBOX_PATH ls -1 $CHROOT_PATH 2>/dev/null | $BUSYBOX_PATH head -10")
                .exec()
        val fileCount = lsResult.out.size
        Log.d(TAG, "Extraction successful. Files in chroot: $fileCount")
        lsResult.out.forEach { Log.d(TAG, "  - $it") }

        if (onCancelled?.invoke() == true) {
            Log.d(TAG, "Extraction cancelled by user")
            throw CancellationException("Extraction cancelled")
        }

        onProgress(70)
        Log.d(TAG, "=== extractTarGz END ===")
    }

    private fun restoreOwnership(path: String) {
        val uid = android.os.Process.myUid()
        Shell.cmd("$BUSYBOX_PATH chown -R $uid:$uid '$path' 2>/dev/null || true").exec()
    }

    private fun setupChroot() {
        Log.d(TAG, "Setting up chroot environment")

        val commands = listOf(
            "mkdir -p $CHROOT_PATH/proc",
            "mkdir -p $CHROOT_PATH/sys",
            "mkdir -p $CHROOT_PATH/dev",
            "mkdir -p $CHROOT_PATH/dev/pts",
            "mkdir -p $CHROOT_PATH/tmp",
            "chmod 755 $CHROOT_PATH",
            "chmod 1777 $CHROOT_PATH/tmp",
            "$BUSYBOX_PATH chmod -R 755 $CHROOT_PATH/bin $CHROOT_PATH/sbin $CHROOT_PATH/usr/bin $CHROOT_PATH/usr/sbin 2>/dev/null || true",
            "$BUSYBOX_PATH chmod -R 755 $CHROOT_PATH/opt 2>/dev/null || true",
            "$BUSYBOX_PATH mknod $CHROOT_PATH/dev/urandom c 1 9 2>/dev/null || true",
            "$BUSYBOX_PATH mknod $CHROOT_PATH/dev/random c 1 8 2>/dev/null || true"
        )

        commands.forEach { command ->
            val result = Shell.cmd(command).exec()
            Log.d(TAG, "$command → ${if (result.isSuccess) "OK" else "FAIL"}")
            if (!result.isSuccess) {
                result.err.forEach { Log.w(TAG, "  $it") }
            }
        }

        restoreOwnership(CHROOT_PATH)

        Log.d(TAG, "Chroot setup completed")
    }

    fun mountChroot(): Boolean {
        synchronized(mountLock) {

            val now = System.currentTimeMillis()
            if (mountFailedAt > 0 && now - mountFailedAt < MOUNT_FAILURE_COOLDOWN_MS) {
                val remaining = MOUNT_FAILURE_COOLDOWN_MS - (now - mountFailedAt)
                Log.d(TAG, "Mount skipped — cooldown active (${remaining}ms remaining)")
                return false
            }

            if (isChrootMounted) {
                if (isPathMounted("$CHROOT_PATH/dev") && isPathMounted("$CHROOT_PATH/proc")) {
                    Log.d(TAG, "Chroot already mounted")
                    incrementMountRef()
                    return true
                }
                Log.w(
                    TAG,
                    "isChrootMounted=true but /dev or /proc not actually mounted — re-mounting"
                )
            }



            cleanupStaleMounts()


            Log.d(TAG, "Cleaning up orphaned chroot processes...")
            Shell.cmd(
                "CHROOT='$CHROOT_PATH'; " +
                        "for p in /proc/[0-9]*; do " +
                        "  r=\"$(readlink \"\$p/root\" 2>/dev/null)\"; " +
                        "  if [ -n \"\$r\" ] && [ \"\$r\" = \"\$CHROOT\" ] 2>/dev/null; then " +
                        "    pid=\"\$(basename \"\$p\")\"; " +
                        "    if [ \"\$pid\" != \"\$\$\" ] && [ \"\$pid\" != \"1\" ]; then " +
                        "      kill -9 \"\$pid\" 2>/dev/null; " +
                        "    fi; " +
                        "  fi; " +
                        "done; " +
                        "echo CLEANUP_DONE"
            ).exec()

            Log.d(TAG, "Mounting chroot with external busybox")


            Shell.cmd("$BUSYBOX_PATH mount -o bind,exec $CHROOT_PATH $CHROOT_PATH 2>/dev/null || true")
                .exec()
                .also { Log.d(TAG, "bind,exec mount → ${if (it.isSuccess) "OK" else "SKIP"}") }


            if (now - mountCheckTimestamp < mountCheckCooldownMs) {
                Log.d(
                    TAG,
                    "Mount check skipped (cooldown ${now - mountCheckTimestamp}ms < ${mountCheckCooldownMs}ms)"
                )
                return true
            }
            mountCheckTimestamp = now


            val mountPaths = listOf(
                "$CHROOT_PATH/dev",
                "$CHROOT_PATH/dev/pts",
                "$CHROOT_PATH/dev/shm",
                "$CHROOT_PATH/proc",
                "$CHROOT_PATH/sys",
                "$CHROOT_PATH/system"
            )

            var allMounted = true
            for (path in mountPaths) {
                if (!isPathMounted(path)) {
                    allMounted = false
                    Log.d(TAG, "Mount check: $path not found in /proc/mounts")
                    break
                }
            }

            if (allMounted) {
                Log.d(TAG, "All filesystems already mounted")
                isChrootMounted = true
                incrementMountRef()
                return true
            }


            val coreMounts = listOf(
                "$BUSYBOX_PATH mount --bind /dev $CHROOT_PATH/dev",
                "$BUSYBOX_PATH mount -t devpts devpts $CHROOT_PATH/dev/pts"
            )

            for (command in coreMounts) {
                val result = Shell.cmd(command).exec()
                Log.d(TAG, "Core mount: $command → ${if (result.isSuccess) "OK" else "FAIL"}")
                if (!result.isSuccess) {
                    result.err.forEach { Log.w(TAG, "  $it") }
                    Log.e(TAG, "Core mount failed, aborting")
                    mountFailedAt = System.currentTimeMillis()
                    return false
                }
            }


            val optionalMounts = listOf(
                "$BUSYBOX_PATH mkdir -p $CHROOT_PATH/tmp",
                "$BUSYBOX_PATH mount -t tmpfs tmpfs $CHROOT_PATH/tmp",
                "$BUSYBOX_PATH mount -t sysfs sysfs $CHROOT_PATH/sys",
                "$BUSYBOX_PATH mount -o rw,nosuid,nodev,mode=1777 -t tmpfs tmpfs $CHROOT_PATH/dev/shm",
                "$BUSYBOX_PATH mount -o remount,suid /data"
            )

            for (command in optionalMounts) {
                val result = Shell.cmd(command).exec()
                Log.d(TAG, "Optional mount: $command → ${if (result.isSuccess) "OK" else "SKIP"}")

            }




            Shell.cmd("$BUSYBOX_PATH mkdir -p $CHROOT_PATH/proc").exec()
            val procMount =
                Shell.cmd("$BUSYBOX_PATH mount -t proc proc $CHROOT_PATH/proc 2>/dev/null").exec()
            if (procMount.isSuccess) {
                Log.d(TAG, "Proc mount → OK")
            } else {
                Log.w(TAG, "mount -t proc failed, trying bind mount of host /proc")
                Shell.cmd("$BUSYBOX_PATH mount --bind /proc $CHROOT_PATH/proc 2>/dev/null").exec()
                    .also { Log.d(TAG, "Proc bind mount → ${if (it.isSuccess) "OK" else "SKIP"}") }
            }
            Log.d(TAG, "Proc mounted: ${isPathMounted("$CHROOT_PATH/proc")}")


            val procDevCheck =
                Shell.cmd("$BUSYBOX_PATH cat $CHROOT_PATH/proc/net/dev 2>/dev/null | $BUSYBOX_PATH grep -Eo 'wlan[0-9]+' | $BUSYBOX_PATH sort -u | $BUSYBOX_PATH head -5")
                    .exec()
            val interfaces = procDevCheck.out.filter { it.isNotBlank() }
            Log.d(TAG, "Chroot /proc/net/dev wifi interfaces: ${interfaces.joinToString(", ")}")


            val postMountSetup = listOf(
                "ln -sf /proc/self/fd $CHROOT_PATH/dev/fd",
                "ln -sf /proc/self/fd/0 $CHROOT_PATH/dev/stdin",
                "ln -sf /proc/self/fd/1 $CHROOT_PATH/dev/stdout",
                "ln -sf /proc/self/fd/2 $CHROOT_PATH/dev/stderr",
                "test -c $CHROOT_PATH/dev/urandom || mknod $CHROOT_PATH/dev/urandom c 1 9 2>/dev/null || true",
                "test -c $CHROOT_PATH/dev/random || mknod $CHROOT_PATH/dev/random c 1 8 2>/dev/null || true"
            )

            postMountSetup.forEach { cmd ->
                val result = Shell.cmd(cmd).exec()
                Log.d(TAG, "Post-mount setup: $cmd → ${if (result.isSuccess) "OK" else "WARN"}")
            }


            val systemResult =
                Shell.cmd("$BUSYBOX_PATH mount -o bind /system $CHROOT_PATH/system").exec()
            Log.d(TAG, "mount /system → ${if (systemResult.isSuccess) "OK" else "SKIP"}")


            Shell.cmd("$BUSYBOX_PATH mkdir -p $CHROOT_PATH/sdcard").exec()

            if (!checkUnshare()) {

                Log.d(TAG, "unshare unavailable, mounting sdcard globally (fallback)")
                val sdcardDirs = listOf(
                    "/storage/emulated/0",
                    "/storage/emulated/legacy",
                    "/storage/sdcard0",
                    "/sdcard"
                )
                for (sdcardDir in sdcardDirs) {
                    if (File(sdcardDir).exists()) {
                        val result =
                            Shell.cmd("$BUSYBOX_PATH mount -o bind $sdcardDir $CHROOT_PATH/sdcard && $BUSYBOX_PATH mount --make-rslave $CHROOT_PATH/sdcard")
                                .exec()
                        if (result.isSuccess) {
                            Log.d(TAG, "mounted sdcard from $sdcardDir with rslave (fallback)")
                            break
                        }
                    }
                }
            } else {
                Log.d(TAG, "sdcard will be mounted per-command via unshare namespace isolation")
            }


            val tunResult =
                Shell.cmd("[ ! -e \"/dev/net/tun\" ] && (mkdir -p /dev/net && mknod /dev/net/tun c 10 200)")
                    .exec()
            Log.d(TAG, "tun device: ${if (tunResult.isSuccess) "OK" else "SKIP"}")

            isChrootMounted = true
            incrementMountRef()
            Log.d(TAG, "Chroot mounted successfully")

            return true
        }
    }

    fun unmountChroot(): Boolean {
        synchronized(mountLock) {
            val shouldUnmount = decrementMountRef()
            if (!shouldUnmount) {
                Log.d(TAG, "Mount ref count: ${mountRefCounter.get()}, skipping actual unmount")
                return true
            }

            Log.d(TAG, "Last mount reference — unmounting chroot")

            val umountCommands = listOf(
                "$BUSYBOX_PATH umount $CHROOT_PATH/tmp 2>/dev/null || true",
                "$BUSYBOX_PATH umount $CHROOT_PATH/sdcard 2>/dev/null || true",
                "$BUSYBOX_PATH umount $CHROOT_PATH/system 2>/dev/null || true",
                "$BUSYBOX_PATH umount $CHROOT_PATH/dev/shm 2>/dev/null || true",
                "$BUSYBOX_PATH umount $CHROOT_PATH/dev/pts 2>/dev/null || true",
                "$BUSYBOX_PATH umount $CHROOT_PATH/dev 2>/dev/null || true",
                "$BUSYBOX_PATH umount $CHROOT_PATH/sys 2>/dev/null || true",
                "$BUSYBOX_PATH umount $CHROOT_PATH/proc 2>/dev/null || true"
            )

            umountCommands.forEach { command ->
                val result = Shell.cmd(command).exec()
                Log.d(TAG, "$command → ${if (result.isSuccess) "OK" else "WARN"}")
            }

            isChrootMounted = false
            Log.d(TAG, "Unmount completed")
            return true
        }
    }

    fun executeInChroot(command: String): Shell.Result {
        Log.d(TAG, "Executing in chroot: $command")
        if (!isChrootMounted) {
            if (!mountChroot()) {
                Log.w(TAG, "Mount failed, returning failure for: $command")
                return mountFailedResult()
            }
        } else {
            Log.d(TAG, "Chroot already mounted, executing directly")
        }

        val shellCmd = if (checkUnshare()) {
            val escaped = command
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("`", "\\`")
                .replace("$", "\\$")
            val fullCmd =
                "unset LD_PRELOAD; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $escaped"
            val chrootBin = chrootBin()
            "$BUSYBOX_PATH unshare -m sh -c 'mkdir -p $CHROOT_PATH/sdcard; mount -o bind /storage/emulated/0 $CHROOT_PATH/sdcard 2>/dev/null; exec $chrootBin $CHROOT_PATH /bin/busybox sh -c \"$fullCmd\"'"
        } else {
            val chrootBin = chrootBin()
            val escapedCommand = command.replace("'", "'\\''")
            val fullCmd =
                "unset LD_PRELOAD; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $escapedCommand"
            "$chrootBin $CHROOT_PATH /bin/busybox sh -c '$fullCmd'"
        }

        Log.d(TAG, "Shell command: $shellCmd")
        shellSemaphore.acquire()
        return try {
            Shell.cmd(shellCmd).exec().also { result ->
                Log.d(TAG, "Result: code=${result.code}, out=${result.out.take(3)}")
            }
        } finally {
            shellSemaphore.release()
        }
    }

    suspend fun copyFileFromChrootToFilesDir(
        chrootPath: String,
        destRelativePath: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val destFile = File(context.filesDir, destRelativePath)
            destFile.parentFile?.mkdirs()
            val destPath = destFile.absolutePath
            val timestamp = System.currentTimeMillis()
            val tempName = "copy_${timestamp}_${chrootPath.hashCode().toUInt()}"
            val chrootTempPath = "/tmp/$tempName"

            val stageCmd = "cp '$chrootPath' '$chrootTempPath' 2>&1"
            val stageResult = executeInChroot(stageCmd)
            if (!stageResult.isSuccess) {
                Log.e(TAG, "copyFileFromChrootToFilesDir: stage failed: code=${stageResult.code}")
                return@withContext null
            }

            val chrootFullPath = "$CHROOT_PATH$chrootTempPath"
            val copyCmd = "cat '$chrootFullPath' > '$destPath' 2>&1 && echo COPY_OK"
            val copyResult = Shell.cmd(copyCmd).exec()

            val cleanupCmd = "rm -f '$chrootTempPath' 2>/dev/null"
            executeInChroot(cleanupCmd)

            if (copyResult.isSuccess && copyResult.out.any { it == "COPY_OK" } &&
                destFile.exists() && destFile.length() > 0) {
                Log.d(
                    TAG,
                    "copyFileFromChrootToFilesDir: $chrootPath -> $destPath (${destFile.length()}B)"
                )
                destPath
            } else {
                Log.e(
                    TAG,
                    "copyFileFromChrootToFilesDir failed: code=${copyResult.code} exists=${destFile.exists()}"
                )
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyFileFromChrootToFilesDir error", e)
            null
        }
    }

    private val hasRootAccess: Boolean
        get() = _hasRootAccess ?: run {
            val result = try {
                Shell.cmd("id").exec().out.any { it.contains("uid=0") }
            } catch (_: Exception) {
                false
            }
            _hasRootAccess = result
            result
        }

    @Volatile
    private var _hasRootAccess: Boolean? = null

    fun resetRootCache() {
        _hasRootAccess = null
    }

    fun resetChrootCaches() {
        _hasRootAccess = null
        chrootTypeCache = ChrootType.None
        chrootTypeCacheTime = 0
    }

    fun resetMountFailedCooldown() {
        synchronized(mountLock) {
            mountFailedAt = 0
        }
    }

    data class ExecutionResult(
        val stdout: List<String>,
        val stderr: List<String>,
        val process: Process?
    )

    suspend fun executeInChrootWithRoot(
        command: String,
        env: Map<String, String> = emptyMap(),
        onOutput: ((String) -> Unit)? = null,
        sessionTimeout: Long = 120_000
    ): Pair<List<String>, List<String>> {
        val result = executePersistentSession(command, env, onOutput, sessionTimeout)
        return result.stdout to result.stderr
    }

    suspend fun executePersistentBatch(
        onOutput: ((String) -> Unit)? = null,
        callback: suspend (BatchExecutor) -> List<RouterScanResult>
    ): List<RouterScanResult> = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== executePersistentBatch START ===")

        sessionCancelled = false
        sessionCleaned = false

        if (!mountChroot()) {
            Log.e(TAG, "Chroot mount failed")
            return@withContext emptyList()
        }

        val process = Runtime.getRuntime().exec("su --mount-master")
        val stdin = process.outputStream
        val stdoutLines = Collections.synchronizedList(mutableListOf<String>())
        val stderrLines = Collections.synchronizedList(mutableListOf<String>())

        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

        val stdoutThread = Thread {
            var line: String?
            try {
                while (stdoutReader.readLine().also { line = it } != null) {
                    stdoutLines.add(line!!)
                    Log.d(TAG, "stdout: $line")
                    onOutput?.invoke(line!!)
                }
            } catch (e: InterruptedIOException) {
                Log.d(TAG, "stdoutThread: stream closed (normal teardown)")
            } catch (e: IOException) {
                Log.e(TAG, "stdoutThread IOException", e)
            }
        }
        val stderrThread = Thread {
            var line: String?
            try {
                while (stderrReader.readLine().also { line = it } != null) {
                    stderrLines.add(line!!)
                    Log.w(TAG, "stderr: $line")
                    onOutput?.invoke("[stderr] $line")
                }
            } catch (e: InterruptedIOException) {
                Log.d(TAG, "stderrThread: stream closed (normal teardown)")
            } catch (e: Exception) {
                Log.e(TAG, "stderrThread error", e)
            }
        }
        stdoutThread.start()
        stderrThread.start()

        if (checkUnshare()) {

            stdin.write("$BUSYBOX_PATH unshare -m sh\n".toByteArray())
            stdin.flush()
            delay(100)
            stdin.write("mkdir -p $CHROOT_PATH/sdcard; mount -o bind /storage/emulated/0 $CHROOT_PATH/sdcard 2>/dev/null\n".toByteArray())
            stdin.flush()
            delay(50)
            val chrootBin = chrootBin()
            val chrootCmd = "exec $chrootBin $CHROOT_PATH /usr/bin/sudo -E PATH=\$PATH bash"
            stdin.write(chrootCmd.toByteArray())
            stdin.write("\n".toByteArray())
            stdin.flush()
            delay(100)
        } else {
            val chrootBin = chrootBin()
            val chrootCmd = "$chrootBin $CHROOT_PATH /usr/bin/sudo -E PATH=\$PATH bash"
            stdin.write(chrootCmd.toByteArray())
            stdin.write("\n".toByteArray())
            stdin.flush()
            delay(100)
        }

        val envCmd =
            "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; unset LD_PRELOAD; mkdir -p /tmp; chmod 1777 /tmp; export TMPDIR=/tmp"
        stdin.write(envCmd.toByteArray())
        stdin.write("\n".toByteArray())
        stdin.flush()
        delay(50)

        val executor = object : BatchExecutor {
            override suspend fun executeSync(cmd: String, timeout: Long): List<String> {
                val tmpFile = "/tmp/chroot_batch_${System.currentTimeMillis()}.out"
                val fullCmd =
                    "timeout ${timeout / 1000} sh -c \"$cmd\" > $tmpFile 2>&1; echo __SYNC_DONE__"
                stdin.write(fullCmd.toByteArray())
                stdin.write("\n".toByteArray())
                stdin.flush()

                val deadline = System.currentTimeMillis() + timeout + 5000
                var pollInterval = 100L
                while (System.currentTimeMillis() < deadline) {
                    synchronized(stdoutLines) {
                        if (stdoutLines.any { it.contains("__SYNC_DONE__") }) break
                    }
                    delay(pollInterval)
                    pollInterval = (pollInterval * 2).coerceAtMost(500)
                }

                val catCmd = "cat $tmpFile; rm -f $tmpFile"
                stdin.write(catCmd.toByteArray())
                stdin.write("\n".toByteArray())
                stdin.flush()
                delay(50)

                return synchronized(stdoutLines) {
                    val doneIdx = stdoutLines.indexOfLast { it.contains("__SYNC_DONE__") }
                    val linesAfter = stdoutLines.drop(doneIdx + 1)
                    linesAfter.takeWhile { it != "bash-" }.toList()
                }
            }

            override suspend fun executeParallel(
                commands: Map<String, String>,
                timeout: Long,
                maxThreads: Int,
                onTargetCompleted: ((String, List<String>) -> Unit)?
            ): Map<String, List<String>> {
                if (commands.isEmpty()) return emptyMap()

                val tmpFiles = commands.mapValues { (ip, _) ->
                    "/tmp/rs_${ip.replace(".", "_")}.out"
                }


                val baseDeadline = System.currentTimeMillis() + timeout
                val ipDeadlines = commands.keys.associateWith { baseDeadline }.toMutableMap()
                val ipPids = mutableMapOf<String, Int>()
                val collectedTargets = mutableSetOf<String>()


                for ((ip, cmd) in commands) {
                    val tmpFile = tmpFiles[ip]!!
                    val parallelCmd = "sh -c \"$cmd\" > $tmpFile 2>&1 & echo \$!"
                    Log.d(TAG, "Launching rs: $parallelCmd")
                    stdin.write(parallelCmd.toByteArray())
                    stdin.write("\n".toByteArray())
                    stdin.flush()
                    delay(10)
                }

                val pidLines = mutableListOf<String>()
                val pidDeadline = System.currentTimeMillis() + 3_000
                var pidPollInterval = 50L
                while (System.currentTimeMillis() < pidDeadline && pidLines.size < commands.size) {
                    synchronized(stdoutLines) {
                        for (line in stdoutLines) {
                            if (line.matches(Regex("^\\d+$")) && !pidLines.contains(line)) {
                                pidLines.add(line)
                            }
                        }
                        stdoutLines.clear()
                    }
                    delay(pidPollInterval)
                    pidPollInterval = (pidPollInterval * 2).coerceAtMost(250)
                }
                synchronized(stdoutLines) {
                    var idx = 0
                    for (ip in commands.keys) {
                        if (idx < pidLines.size) {
                            val pid = pidLines[idx].trim().toIntOrNull()
                            if (pid != null) {
                                ipPids[ip] = pid
                                idx++
                            }
                        }
                    }
                }
                Log.d(TAG, "Captured PIDs: $ipPids")


                val batchJob = SupervisorJob()
                val batchContext = batchJob + CoroutineExceptionHandler { _, t ->
                    Log.e(TAG, "parallel batch job error", t)
                }
                val monitorJob = CoroutineScope(Dispatchers.Default + batchContext).launch {
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        val toKill = ipPids.filter { (ip, pid) ->
                            pid != 0 && ipDeadlines[ip] != null && now > ipDeadlines[ip]!!
                        }
                        for ((ip, pid) in toKill) {
                            Log.d(TAG, "Timeout killed rs for $ip (PID $pid)")
                            try {
                                stdin.write("kill $pid 2>/dev/null\n".toByteArray())
                                stdin.flush()
                            } catch (e: IOException) {
                                Log.w(TAG, "monitorJob: stdin closed, stopping")
                                return@launch
                            }
                            ipPids.remove(ip)
                            ipDeadlines.remove(ip)
                        }
                        delay(2000)
                    }
                }
                val statusJob = CoroutineScope(Dispatchers.Default + batchContext).launch {
                    var lastStatus = mutableMapOf<String, String>()
                    while (isActive) {
                        var streamClosed = false
                        for (ip in commands.keys) {
                            val tmpFile = tmpFiles[ip] ?: continue
                            val marker = "STATUS_CHECK_${ip}"
                            try {
                                stdin.write("echo $marker; cat $tmpFile 2>/dev/null | tail -10\necho STATUS_END\n".toByteArray())
                                stdin.flush()
                            } catch (e: IOException) {
                                Log.w(TAG, "statusJob: stdin closed, stopping")
                                streamClosed = true
                                break
                            }
                            delay(200)

                            synchronized(stdoutLines) {
                                val allOut = stdoutLines.joinToString("\n")
                                val startIdx = allOut.indexOf(marker)
                                val endIdx = allOut.indexOf("STATUS_END")
                                if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                                    val content = allOut.substring(startIdx + marker.length, endIdx)
                                    var newDeadline: Long? = null
                                    if (content.contains("Trying to log in")) {
                                        newDeadline = System.currentTimeMillis() + 60000
                                    }
                                    if (content.contains("Getting info")) {
                                        newDeadline = System.currentTimeMillis() + 90000
                                    }
                                    val last = lastStatus[ip]
                                    val statusKey = if (newDeadline != null) "extended" else "none"
                                    if (statusKey != last && newDeadline != null) {
                                        ipDeadlines[ip] = newDeadline
                                        Log.d(
                                            TAG,
                                            "Extended deadline for $ip to ${newDeadline - System.currentTimeMillis()}ms"
                                        )
                                        lastStatus[ip] = statusKey
                                    }
                                }
                            }
                        }
                        if (streamClosed) break
                        delay(3000)
                    }
                }


                suspend fun deliverCompletedTarget(ipPort: String) {
                    val cb = onTargetCompleted ?: return
                    val tmpFile = tmpFiles[ipPort] ?: return
                    val startM = "LINE_START_$ipPort"
                    val endM = "LINE_END_$ipPort"
                    var delivered: List<String>? = null
                    withContext(NonCancellable) {
                        try {
                            stdin.write("echo \"$startM\"; cat $tmpFile 2>/dev/null; echo \"$endM\"\n".toByteArray())
                            stdin.flush()
                        } catch (e: IOException) {
                            Log.w(TAG, "deliver read failed for $ipPort")
                            return@withContext
                        }
                        val deadline = System.currentTimeMillis() + 8_000
                        var waitInterval = 50L
                        while (System.currentTimeMillis() < deadline) {
                            synchronized(stdoutLines) {
                                val allOut = stdoutLines.joinToString("\n")
                                val s = allOut.indexOf(startM)
                                val e = allOut.indexOf(endM)
                                if (s != -1 && e != -1 && e > s) {
                                    val content = allOut.substring(s + startM.length, e)
                                    delivered = content.split("\n").filter { it.isNotEmpty() }
                                    return@withContext
                                }
                            }
                            delay(waitInterval)
                            waitInterval = (waitInterval * 2).coerceAtMost(250)
                        }
                        Log.w(TAG, "Timed out reading output for $ipPort")
                    }
                    if (delivered != null) {
                        collectedTargets.add(ipPort)
                        cb(ipPort, delivered!!)
                    }
                }

                try {
                val maxWait = 120000L
                var elapsed = 0L
                var pollInterval = 1000L
                pollLoop@ while (elapsed < maxWait) {

                    val pidList = ipPids.values.filter { it != 0 }
                    if (pidList.isEmpty()) break


                    for (pid in pidList) {
                        try {
                            stdin.write("kill -0 $pid 2>/dev/null && echo PID_${pid}_ALIVE || echo PID_${pid}_DEAD\n".toByteArray())
                            stdin.flush()
                        } catch (e: IOException) {
                            Log.w(TAG, "poll loop: stdin closed, stopping")
                            break@pollLoop
                        }
                        delay(20)
                    }

                    delay(pollInterval)
                    val completedTargets = mutableListOf<String>()
                    synchronized(stdoutLines) {
                        val alivePids = pidList.filter { pid ->
                            stdoutLines.any { it.contains("PID_${pid}_ALIVE") }
                        }
                        stdoutLines.clear()


                        for (pid in pidList) {
                            if (!alivePids.contains(pid)) {
                                val ipToRemove = ipPids.entries.find { it.value == pid }?.key
                                if (ipToRemove != null) {
                                    ipPids.remove(ipToRemove)
                                    completedTargets.add(ipToRemove)
                                    Log.d(TAG, "Process $pid for $ipToRemove completed")
                                }
                            }
                        }
                    }

                    for (ipPort in completedTargets) {
                        deliverCompletedTarget(ipPort)
                    }
                    synchronized(stdoutLines) { stdoutLines.clear() }

                    synchronized(stdoutLines) {
                        if (ipPids.values.none { it != 0 }) {
                            Log.d(TAG, "All processes completed")
                            break
                        }
                        Log.d(
                            TAG,
                            "${ipPids.values.count { it != 0 }} processes still alive"
                        )
                    }

                    elapsed += pollInterval
                    pollInterval = (pollInterval * 2).coerceAtMost(1500)
                }

                batchJob.cancel()
                delay(500)


                synchronized(stdoutLines) { stdoutLines.clear() }


                val results = mutableMapOf<String, List<String>>()
                val pendingTmpFiles = tmpFiles.filterKeys { it !in collectedTargets }
                if (pendingTmpFiles.isNotEmpty()) {
                    for ((ip, tmpFile) in pendingTmpFiles) {
                        val startMarker = "BATCH_START_${ip}"
                        val endMarker = "BATCH_END_${ip}"
                        try {
                            stdin.write("echo \"$startMarker\"; cat $tmpFile 2>/dev/null; echo \"$endMarker\"\n".toByteArray())
                            stdin.flush()
                        } catch (e: IOException) {
                            Log.w(TAG, "final collection: stdin closed")
                            break
                        }
                    }

                    val collectDeadline = System.currentTimeMillis() + 15_000
                    var collectPollInterval = 100L
                    while (System.currentTimeMillis() < collectDeadline) {
                        synchronized(stdoutLines) {
                            val allOut = stdoutLines.joinToString("\n")
                            val allMarkersPresent = pendingTmpFiles.keys.all { ip ->
                                val s = allOut.indexOf("BATCH_START_${ip}")
                                val e = allOut.indexOf("BATCH_END_${ip}")
                                s != -1 && e != -1 && e > s
                            }
                            if (allMarkersPresent) break
                        }
                        delay(collectPollInterval)
                        collectPollInterval = (collectPollInterval * 2).coerceAtMost(500)
                    }

                    synchronized(stdoutLines) {
                        val allOut = stdoutLines.joinToString("\n")
                        for ((ip, tmpFile) in pendingTmpFiles) {
                            val startMarker = "BATCH_START_${ip}"
                            val endMarker = "BATCH_END_${ip}"
                            val startIdx = allOut.indexOf(startMarker)
                            val endIdx = allOut.indexOf(endMarker)
                            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                                val content = allOut.substring(startIdx + startMarker.length, endIdx)
                                results[ip] = content.split("\n").filter { it.isNotEmpty() }
                            } else {
                                results[ip] = emptyList()
                                Log.w(TAG, "No output for $ip")
                            }
                        }
                    }
                }


                val rmCmd = "rm -f ${tmpFiles.values.joinToString(" ")}"
                try {
                    stdin.write(rmCmd.toByteArray())
                    stdin.write("\n".toByteArray())
                    stdin.flush()
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to rm tmp files", e)
                }

                return results
                } finally {
                    withContext(NonCancellable) {
                        monitorJob.cancel()
                        statusJob.cancel()
                        batchJob.cancel()
                        try {
                            withTimeoutOrNull(5_000) { batchJob.join() }
                        } catch (e: Exception) {
                            Log.w(TAG, "join batchJob", e)
                        }
                        for ((ip, pid) in ipPids) {
                            if (pid == 0) continue
                            try {
                                stdin.write("kill $pid 2>/dev/null\n".toByteArray())
                                stdin.flush()
                            } catch (e: IOException) {
                                Log.w(TAG, "final kill failed for $ip")
                            }
                        }
                        Log.d(TAG, "executeParallel finally: cleanup done")
                    }
                }
            }

            override fun extendTimeout(key: String, additionalTimeMs: Long) {
                Log.d(
                    TAG,
                    "extendTimeout called for $key: +${additionalTimeMs}ms (not used - done-file mechanism)"
                )
            }
        }

        return@withContext try {
            callback(executor)
        } finally {
            try {
                stdin.write("exit\n".toByteArray())
                stdin.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send exit in batch execution", e)
            }

            stdoutThread.join(2000)
            stderrThread.join(2000)

            try {
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to destroy process in batch execution", e)
            }

            Log.d(TAG, "=== executePersistentBatch END ===")
        }
    }

    interface BatchExecutor {
        suspend fun executeSync(cmd: String, timeout: Long): List<String>
        suspend fun executeParallel(
            commands: Map<String, String>,
            timeout: Long,
            maxThreads: Int,
            onTargetCompleted: ((String, List<String>) -> Unit)? = null
        ): Map<String, List<String>>

        fun extendTimeout(key: String, additionalTimeMs: Long)
    }

    private fun sanitizeCommand(command: String): String {
        val SAFE_COMMAND_REGEX = Regex("^[a-zA-Z0-9\\s/\\._:\\|\\(\\)\\?\\*\\[\\]\\^@\\$\\\\\\-]+$")
        if (!SAFE_COMMAND_REGEX.matches(command)) {
            Log.w(TAG, "Command contains potentially dangerous characters, sanitizing: $command")
        }

        return command
            .replace("'", "'\\''")
            .replace("`", "\\`")
            .replace("$(", "\${}")
    }

    @Volatile
    private var sessionCancelled = false

    @Volatile
    private var sessionCleaned = false

    @Volatile
    private var daemonProcess: Process? = null

    @Volatile
    private var daemonStdin: OutputStream? = null

    @Volatile
    private var daemonStdoutThread: Thread? = null

    @Volatile
    private var daemonStderrThread: Thread? = null

    @Volatile
    private var daemonRunning = false
    private val daemonStdoutLines = Collections.synchronizedList(mutableListOf<String>())
    private val daemonStderrLines = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var suPath: String? = null

    @Volatile
    private var stopDaemonInProgress = false

    private fun resolveSuPath(): String {
        suPath?.let { return it }
        val candidates = listOf(
            "su", "/system/bin/su", "/system/xbin/su",
            "/sbin/su", "/vendor/bin/su", "/data/adb/magisk/su"
        )
        for (path in candidates) {
            try {
                val result = Shell.cmd("test -x '$path' && echo OK || echo NO").exec()
                if (result.isSuccess && result.out.any { it.trim() == "OK" }) {
                    suPath = path
                    return path
                }
            } catch (_: Exception) {
            }
        }
        suPath = "su"
        return "su"
    }

    suspend fun executePersistentSession(
        command: String,
        env: Map<String, String> = emptyMap(),
        onOutput: ((String) -> Unit)? = null,
        sessionTimeout: Long = 120_000
    ): ExecutionResult = withContext(Dispatchers.IO) {
        val stdoutLines = Collections.synchronizedList(mutableListOf<String>())
        val stderrLines = Collections.synchronizedList(mutableListOf<String>())
        var process: Process? = null
        var stdin: OutputStream? = null
        var stdoutReader: BufferedReader? = null
        var stderrReader: BufferedReader? = null
        var stdoutThread: Thread? = null
        var stderrThread: Thread? = null
        var commandCompleted = false
        val startTime = System.currentTimeMillis()

        Log.d(TAG, "=== executePersistentSession START ===")
        Log.d(TAG, "Command: $command")


        sessionCancelled = false
        sessionCleaned = false

        try {
            Log.d(TAG, "Step 1: Mounting chroot...")
            val mountResult = mountChroot()
            Log.d(TAG, "Mount result: $mountResult")
            if (!mountResult) {
                Log.e(TAG, "Chroot mount failed — aborting")
                return@withContext ExecutionResult(
                    emptyList(),
                    listOf("Chroot mount failed"),
                    null
                )
            }

            Log.d(TAG, "Step 2: Starting su --mount-master...")
            process = Runtime.getRuntime().exec("su --mount-master")
            stdin = process.outputStream
            stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            stderrReader = BufferedReader(InputStreamReader(process.errorStream))
            Log.d(TAG, "Step 2: su process started")


            stdoutThread = Thread {
                Log.d(TAG, "stdoutThread: started")
                var line: String?
                try {
                    while (stdoutReader?.readLine().also { line = it } != null) {
                        stdoutLines.add(line!!)
                        Log.d(TAG, "stdout: $line")
                        try {
                            onOutput?.invoke(line!!)
                        } catch (e: Exception) {
                            Log.e(TAG, "stdout callback threw exception", e)
                        }
                    }
                } catch (e: InterruptedIOException) {
                    Log.d(TAG, "stdoutThread: stream closed (normal teardown)")
                } catch (e: IOException) {
                    Log.e(TAG, "stdoutThread IOException", e)
                }
                Log.d(TAG, "stdoutThread: finished, total lines: ${stdoutLines.size}")
            }
            stderrThread = Thread {
                Log.d(TAG, "stderrThread: started")
                var line: String?
                try {
                    while (stderrReader?.readLine().also { line = it } != null) {
                        stderrLines.add(line!!)
                        Log.w(TAG, "stderr: $line")
                        try {
                            onOutput?.invoke("[stderr] $line")
                        } catch (e: Exception) {
                            Log.e(TAG, "stderr callback threw exception", e)
                        }
                    }
                } catch (e: InterruptedIOException) {
                    Log.d(TAG, "stderrThread: stream closed (normal teardown)")
                } catch (e: Exception) {
                    Log.e(TAG, "stderrThread error", e)
                }
                Log.d(TAG, "stderrThread: finished, total lines: ${stderrLines.size}")
            }
            stdoutThread.start()
            stderrThread.start()

            Log.d(TAG, "Step 3: Entering chroot (ns-isolated=${checkUnshare()})...")
            if (checkUnshare()) {

                stdin.write("$BUSYBOX_PATH unshare -m sh\n".toByteArray())
                stdin.flush()
                delay(100)
                stdin.write("mkdir -p $CHROOT_PATH/sdcard; mount -o bind /storage/emulated/0 $CHROOT_PATH/sdcard 2>/dev/null\n".toByteArray())
                stdin.flush()
                delay(50)
                stdin.write("mount --bind /dev/urandom $CHROOT_PATH/dev/urandom 2>/dev/null; mount --bind /dev/random $CHROOT_PATH/dev/random 2>/dev/null; true\n".toByteArray())
                stdin.flush()
                delay(50)
                val chrootBin = chrootBin()
                val chrootCmd = "exec $chrootBin $CHROOT_PATH /usr/bin/sudo -E PATH=\$PATH bash"
                stdin.write(chrootCmd.toByteArray())
                stdin.write("\n".toByteArray())
                stdin.flush()
                delay(100)
            } else {
                val chrootBin = chrootBin()
                val chrootCmd = "$chrootBin $CHROOT_PATH /usr/bin/sudo -E PATH=\$PATH bash"
                Log.d(TAG, "Step 3 (fallback): Entering chroot via: $chrootCmd")
                stdin.write(chrootCmd.toByteArray())
                stdin.write("\n".toByteArray())
                stdin.flush()
                delay(100)
            }

            val customEnv = env.entries.joinToString("; ") { "export ${it.key}=${it.value}" }
            val envCmd =
                "unset LD_PRELOAD; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export TMPDIR=/tmp${if (customEnv.isNotEmpty()) "; $customEnv" else ""}"
            Log.d(TAG, "Step 4: Setting environment: $envCmd")
            stdin.write(envCmd.toByteArray())
            stdin.write("\n".toByteArray())
            stdin.flush()
            delay(50)


            val sanitizedCommand = sanitizeCommand(command)
            Log.d(TAG, "Step 5: Executing command: $sanitizedCommand")
            stdin.write(sanitizedCommand.toByteArray())
            stdin.write("\n".toByteArray())
            stdin.flush()


            try {
                stdin.write("exit\n".toByteArray())
                stdin.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send exit after command", e)
            }

            Log.d(TAG, "Waiting for command to complete...")
            val timeoutDeadline = System.currentTimeMillis() + sessionTimeout
            var pollInterval = 100L
            while (!commandCompleted && System.currentTimeMillis() < timeoutDeadline) {
                if (sessionCancelled) {
                    Log.w(TAG, "Session cancelled — exiting (cleanup handled by forceCleanup)")
                    commandCompleted = true
                    break
                }
                delay(pollInterval)
                pollInterval = (pollInterval * 2).coerceAtMost(500)
                synchronized(stdoutLines) {
                    if (stdoutLines.any { it.contains("PIXIE_DONE") }) {
                        commandCompleted = true
                        Log.d(TAG, "Command completed (PIXIE_DONE detected)")
                    }
                    if (stdoutLines.any { it.contains("Status: Done") }) {
                        commandCompleted = true
                        Log.d(TAG, "Command completed (Status: Done detected)")

                        try {
                            stdin.write("exit\n".toByteArray())
                            stdin.flush()
                            Log.d(TAG, "Sent exit command after Status: Done")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send exit after Status: Done", e)
                        }
                    }
                    if (stdoutLines.any { it.contains("Status: Can't load main page") }) {
                        commandCompleted = true
                        Log.d(TAG, "Command completed (Status: Can't load main page detected)")

                        try {
                            stdin.write("exit\n".toByteArray())
                            stdin.flush()
                            Log.d(TAG, "Sent exit command after Status: Can't load main page")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send exit after main page error", e)
                        }
                    }
                    if (stdoutLines.any { it.contains("__SCAN_DONE__") }) {
                        commandCompleted = true
                        Log.d(TAG, "Command completed (__SCAN_DONE__ detected)")
                    }
                    if (stdoutLines.any { it.contains("AIRODUMP_DONE") }) {
                        commandCompleted = true
                        Log.d(TAG, "Command completed (AIRODUMP_DONE detected)")
                    }
                }

                try {
                    process.exitValue()
                    commandCompleted = true
                    Log.d(TAG, "Command completed (process exited)")
                } catch (_: IllegalThreadStateException) {

                }
            }

            if (!commandCompleted) {
                Log.w(TAG, "Command timed out — force cleanup")
                forceCleanup()
            }


            Log.d(TAG, "Destroying process tree after command completion")
            try {
                process.destroyForcibly()
                Shell.cmd("killall -9 airodump-ng 2>/dev/null; killall -9 aireplay-ng 2>/dev/null")
                    .exec()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to destroy process after command completion", e)
            }

            Log.d(TAG, "Step 7: Process destroyed forcibly")

            Log.d(TAG, "Step 8: Closing stdin and joining threads...")
            try {
                stdin?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close stdin", e)
            }
            stdoutThread.join(5000)
            stderrThread.join(5000)


            try {
                val exitCode = process.exitValue()
                Log.d(TAG, "Process exit code: $exitCode")
            } catch (_: IllegalThreadStateException) {
                Log.w(TAG, "Could not get exit code (process may have been destroyed)")
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(
                TAG,
                "=== executePersistentSession END (elapsed=${elapsed}ms, stdoutLines=${stdoutLines.size}, stderrLines=${stderrLines.size}) ==="
            )
            if (stdoutLines.isNotEmpty()) {
                Log.d(TAG, "First 5 stdout lines: ${stdoutLines.take(5)}")
            }
            if (stderrLines.isNotEmpty()) {
                Log.d(TAG, "First 5 stderr lines: ${stderrLines.take(5)}")
            }

        } catch (e: CancellationException) {
            Log.w(
                TAG,
                "su --mount-master execution cancelled after ${System.currentTimeMillis() - startTime}ms"
            )
            cancelSession()

            try {
                Log.d(TAG, "Closing stdin and destroying su process on cancellation")
                stdin?.close()
                process?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close/destroy process on cancellation", e)
            }
            forceCleanup()
            throw e
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "su --mount-master execution failed after ${elapsed}ms", e)
            stderrLines.add("ERROR: ${e.message}")
            try {
                stdin?.write("exit\nexit\n".toByteArray())
                stdin?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send exit on error", e)
            }
        } finally {
            Log.d(TAG, "Step 9: Cleaning up resources...")
            try {
                stdoutReader?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close stdoutReader", e)
            }
            try {
                stderrReader?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close stderrReader", e)
            }
            try {
                stdin?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close stdin in finally", e)
            }
            if (!sessionCleaned) {
                Log.d(TAG, "Step 10: Unmounting chroot...")
                try {
                    unmountChroot()
                } catch (e: Exception) {
                    Log.e(TAG, "Unmount failed during cleanup", e)
                }
            } else {
                Log.d(TAG, "Step 10: Skipping unmount — already cleaned up")
            }
            sessionCancelled = false
            sessionCleaned = false
            Log.d(TAG, "=== executePersistentSession COMPLETE ===")
        }

        ExecutionResult(stdoutLines, stderrLines, process)
    }

    suspend fun executeDaemonSession(
        command: String,
        env: Map<String, String> = emptyMap(),
        onOutput: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (daemonRunning) {
            Log.w(TAG, "Daemon session already running")
            return@withContext false
        }

        Log.d(TAG, "=== executeDaemonSession START ===")
        Log.d(TAG, "Command: $command")

        daemonRunning = false
        daemonStdoutLines.clear()
        daemonStderrLines.clear()

        try {
            Log.d(TAG, "Step 1: Mounting chroot...")
            val mountResult = mountChroot()
            if (!mountResult) {
                Log.e(TAG, "Chroot mount failed — aborting daemon")
                return@withContext false
            }

            Log.d(TAG, "Step 2: Starting su --mount-master...")
            val suCmd = "${resolveSuPath()} --mount-master"
            Log.d(TAG, "Using su path: $suCmd")
            val process = Runtime.getRuntime().exec(suCmd)
            val stdin = process.outputStream
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            daemonProcess = process
            daemonStdin = stdin

            val stdoutThread = Thread {
                var line: String?
                try {
                    while (stdoutReader.readLine().also { line = it } != null) {
                        daemonStdoutLines.add(line!!)
                        Log.d(TAG, "daemon stdout: $line")
                        try {
                            onOutput?.invoke(line!!)
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: IOException) {
                    if (!daemonRunning) Log.d(TAG, "daemon stdout thread: stream closed")
                    else Log.e(TAG, "daemon stdout thread error", e)
                }
            }

            val stderrThread = Thread {
                var line: String?
                try {
                    while (stderrReader.readLine().also { line = it } != null) {
                        daemonStderrLines.add(line!!)
                        Log.w(TAG, "daemon stderr: $line")
                        try {
                            onOutput?.invoke("[stderr] $line")
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                    if (!daemonRunning) Log.d(TAG, "daemon stderr thread: stream closed")
                }
            }

            daemonStdoutThread = stdoutThread
            daemonStderrThread = stderrThread
            stdoutThread.start()
            stderrThread.start()

            Log.d(TAG, "Step 3: Entering chroot...")
            if (checkUnshare()) {
                stdin.write("$BUSYBOX_PATH unshare -m sh\n".toByteArray())
                stdin.flush()
                delay(100)
                stdin.write("mkdir -p $CHROOT_PATH/sdcard; mount -o bind /storage/emulated/0 $CHROOT_PATH/sdcard 2>/dev/null\n".toByteArray())
                stdin.flush()
                delay(50)
                stdin.write("mount --bind /dev/urandom $CHROOT_PATH/dev/urandom 2>/dev/null; mount --bind /dev/random $CHROOT_PATH/dev/random 2>/dev/null; true\n".toByteArray())
                stdin.flush()
                delay(50)
                val chrootBin = chrootBin()
                val chrootCmd = "exec $chrootBin $CHROOT_PATH /usr/bin/sudo -E PATH=\$PATH bash"
                stdin.write(chrootCmd.toByteArray())
                stdin.write("\n".toByteArray())
                stdin.flush()
                delay(100)
            } else {
                val chrootBin = chrootBin()
                val chrootCmd = "$chrootBin $CHROOT_PATH /usr/bin/sudo -E PATH=\$PATH bash"
                stdin.write(chrootCmd.toByteArray())
                stdin.write("\n".toByteArray())
                stdin.flush()
                delay(100)
            }

            val customEnv = env.entries.joinToString("; ") { "export ${it.key}=${it.value}" }
            val envCmd =
                "unset LD_PRELOAD; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export TMPDIR=/tmp${if (customEnv.isNotEmpty()) "; $customEnv" else ""}"
            stdin.write(envCmd.toByteArray())
            stdin.write("\n".toByteArray())
            stdin.flush()
            delay(50)

            val sanitizedCommand = sanitizeCommand(command)
            Log.d(TAG, "Step 4: Executing daemon command: $sanitizedCommand")
            stdin.write(sanitizedCommand.toByteArray())
            stdin.write("\n".toByteArray())
            stdin.flush()



            daemonRunning = true
            Log.d(TAG, "=== executeDaemonSession STARTED (daemon running) ===")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start daemon session", e)
            try {
                daemonStdin?.write("exit\n".toByteArray()); daemonStdin?.flush()
            } catch (_: Exception) {
            }
            try {
                daemonProcess?.destroy()
            } catch (_: Exception) {
            }
            daemonProcess = null
            daemonStdin = null
            return@withContext false
        }
    }

    fun isDaemonRunning(): Boolean = daemonRunning && daemonProcess?.isAlive == true

    suspend fun stopDaemonSession() = withContext(Dispatchers.IO) {
        if (stopDaemonInProgress) {
            Log.w(TAG, "stopDaemonSession already in progress — skipping")
            return@withContext
        }
        stopDaemonInProgress = true
        Log.d(TAG, "=== stopDaemonSession ===")

        try {
            daemonRunning = false

            try {
                Log.d(TAG, "Killing bettercap in chroot...")
                Shell.cmd("killall -9 bettercap 2>/dev/null").exec()
                delay(200)
            } catch (_: Exception) {
            }

            try {
                daemonStdin?.write("exit\n".toByteArray())
                daemonStdin?.flush()
            } catch (_: Exception) {
            }

            try {
                daemonStdin?.close()
            } catch (_: Exception) {
            }

            try {
                daemonProcess?.destroyForcibly()
            } catch (_: Exception) {
            }

            try {
                daemonStdoutThread?.join(2000)
                daemonStderrThread?.join(2000)
            } catch (_: Exception) {
            }

            daemonProcess = null
            daemonStdin = null
            daemonStdoutThread = null
            daemonStderrThread = null

            forceCleanup()
        } catch (e: Exception) {
            Log.e(TAG, "stopDaemonSession error", e)
        } finally {
            stopDaemonInProgress = false
            Log.d(TAG, "=== stopDaemonSession COMPLETE ===")
        }
    }

    suspend fun getWifiInterfaces(): List<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Getting WiFi interfaces from chroot")
            val (stdout, stderr) = executeInChrootWithRoot("iw dev 2>/dev/null | grep 'Interface' | awk '{print \$2}'")
            val interfaces = (stdout + stderr).filter { it.isNotBlank() }.distinct()
            Log.d(TAG, "Found WiFi interfaces: $interfaces")
            interfaces
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WiFi interfaces", e)
            emptyList()
        }
    }

    private fun testChroot(): Boolean {
        Log.d(TAG, "Testing chroot...")
        val testResult =
            Shell.cmd("${chrootBin()} $CHROOT_PATH /bin/busybox sh -c 'echo CHROOT_ALIVE'").exec()
        val success = testResult.code == 0 && testResult.out.any { it.trim() == "CHROOT_ALIVE" }
        Log.d(TAG, "Chroot test: $success (code=${testResult.code})")
        return success
    }

    private fun saveVersion(version: String) {
        Log.d(TAG, "Saving version: $version")
        val tempFile = File(context.cacheDir, "chroot_version.txt")
        tempFile.writeText(version)
        Shell.cmd("cp '${tempFile.absolutePath}' '$VERSION_FILE_PATH' && chown ${android.os.Process.myUid()}:${android.os.Process.myUid()} '$VERSION_FILE_PATH' 2>/dev/null || true")
            .exec()
        tempFile.delete()
    }

    fun getCurrentVersion(): String? {
        return try {
            val result = Shell.cmd("cat '$VERSION_FILE_PATH'").exec()
            if (result.isSuccess) result.out.joinToString("\n").trim() else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun checkForUpdates(): Boolean {
        val currentVersion = getCurrentVersion()
        val chrootInfo = getChrootInfo()
        val hasUpdate = chrootInfo?.version != currentVersion
        Log.d(
            TAG,
            "Check updates: current=$currentVersion, remote=${chrootInfo?.version}, hasUpdate=$hasUpdate"
        )
        return hasUpdate
    }

    fun isChrootInstalled(): Boolean {
        val installed = rootDirExists(CHROOT_PATH) && rootFileExists("$CHROOT_PATH/bin/busybox")
        Log.d(TAG, "isChrootInstalled: $installed")
        return installed
    }

    fun getChrootType(): ChrootType {
        if (isChrootCheckIgnored()) {
            Log.d(TAG, "getChrootType: chroot check ignored, returning Root")
            return ChrootType.Root
        }
        val now = System.currentTimeMillis()
        if (now - chrootTypeCacheTime < CHROOT_TYPE_CACHE_MS) {
            return chrootTypeCache
        }
        val hasRoot = hasRootAccess
        val rootInstalled = hasRoot && isChrootInstalled()
        Log.d(TAG, "getChrootType: hasRoot=$hasRoot, rootInstalled=$rootInstalled")
        val result = when {
            rootInstalled -> ChrootType.Root
            hasRoot -> {
                if (testChrootSyscall()) {
                    ChrootType.RootMissing
                } else {
                    ChrootType.RootWithoutChroot(resolveRootlessRuntime())
                }
            }

            else -> {
                val rootfsRs = File(context.filesDir, "rootfs/opt/RouterScan/rs")
                if (rootfsRs.exists()) {
                    val rt = resolveRootlessRuntime()
                    ChrootType.Rootless(rt)
                } else {
                    ChrootType.None
                }
            }
        }
        chrootTypeCache = result
        chrootTypeCacheTime = now
        return result
    }

    private fun isChrootCheckIgnored(): Boolean {
        return try {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("ignore_chroot_check", false)
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveRootlessRuntime(): RuntimeType {
        val savedType = context.getSharedPreferences("rootless_prefs", Context.MODE_PRIVATE)
            .getString("runtime_type", null)
        if (savedType != null) {
            try {
                return RuntimeType.valueOf(savedType)
            } catch (_: Exception) {
            }
        }

        val rsCache = File(context.filesDir, "rs_binaries/rs")
        if (rsCache.exists()) {
            return RuntimeType.MUSL_LD
        }

        val nativeLibDir = try {
            val info = context.packageManager.getApplicationInfo(context.packageName, 0)
            info.nativeLibraryDir
        } catch (_: Exception) {
            null
        }

        if (!nativeLibDir.isNullOrBlank() && android.os.Build.VERSION.SDK_INT >= 26) {
            val prorootFile = File(nativeLibDir, "libproroot.so")
            if (prorootFile.exists()) {
                return RuntimeType.PROROOT
            }
        }

        val prootFile = File(context.filesDir, "proot/proot")
        if (prootFile.exists() && prootFile.canExecute()) {
            return RuntimeType.PROOT
        }

        return if (android.os.Build.VERSION.SDK_INT >= 26) RuntimeType.PROROOT else RuntimeType.PROOT
    }

    fun verifyChrootIntegrity(): Boolean {
        Log.d(TAG, "Verifying chroot integrity")
        val exists =
            rootFileExists("$CHROOT_PATH/bin/busybox") && rootCanExecute("$CHROOT_PATH/bin/busybox")
        Log.d(TAG, "busybox exists=$exists")
        return exists
    }

    fun checkChrootBinaries(): Boolean {
        Log.d(TAG, "=== CHECKING CHROOT BINARIES ===")
        var allOk = true

        val python3Exists = rootFileExists("$CHROOT_PATH/usr/bin/python3")
        val python3Exec = rootCanExecute("$CHROOT_PATH/usr/bin/python3")
        Log.d(TAG, "python3: exists=$python3Exists, canExecute=$python3Exec")
        if (!python3Exists || !python3Exec) {
            Log.e(TAG, "FAIL: python3 not found or not executable!")
            allOk = false
        }

        val pixieExists = rootFileExists("$CHROOT_PATH/opt/PixieWps/pixie.py")
        Log.d(TAG, "pixie.py: exists=$pixieExists, path=$CHROOT_PATH/opt/PixieWps/pixie.py")
        if (!pixieExists) {
            Log.e(TAG, "FAIL: pixie.py not found!")
            allOk = false
        }

        val pixiewpsExists = rootFileExists("$CHROOT_PATH/usr/bin/pixiewps")
        val pixiewpsExec = rootCanExecute("$CHROOT_PATH/usr/bin/pixiewps")
        Log.d(TAG, "pixiewps: exists=$pixiewpsExists, canExecute=$pixiewpsExec")
        if (!pixiewpsExists || !pixiewpsExec) {
            Log.e(TAG, "FAIL: pixiewps not found or not executable!")
            allOk = false
        }

        Log.d(TAG, "busybox timeout: /bin/busybox timeout (verified)")

        val airodumpExists = rootFileExists("$CHROOT_PATH/usr/bin/airodump-ng")
        val airodumpExec = rootCanExecute("$CHROOT_PATH/usr/bin/airodump-ng")
        Log.d(TAG, "airodump-ng: exists=$airodumpExists, canExecute=$airodumpExec")
        if (!airodumpExists || !airodumpExec) {
            Log.e(TAG, "WARN: airodump-ng not found — handshake capture unavailable!")
        }

        val aireplayExists = rootFileExists("$CHROOT_PATH/usr/bin/aireplay-ng")
        val aireplayExec = rootCanExecute("$CHROOT_PATH/usr/bin/aireplay-ng")
        Log.d(TAG, "aireplay-ng: exists=$aireplayExists, canExecute=$aireplayExec")
        if (!aireplayExists || !aireplayExec) {
            Log.e(TAG, "WARN: aireplay-ng not found — deauth unavailable!")
        }

        val aircrackExists = rootFileExists("$CHROOT_PATH/usr/bin/aircrack-ng")
        val aircrackExec = rootCanExecute("$CHROOT_PATH/usr/bin/aircrack-ng")
        Log.d(TAG, "aircrack-ng: exists=$aircrackExists, canExecute=$aircrackExec")
        if (!aircrackExists || !aircrackExec) {
            Log.e(TAG, "WARN: aircrack-ng not found — cracking unavailable!")
        }

        val cowpattyExists = rootFileExists("$CHROOT_PATH/usr/bin/cowpatty")
        Log.d(TAG, "cowpatty: exists=$cowpattyExists")
        if (!cowpattyExists) {
            Log.d(TAG, "cowpatty not found — verification via aircrack-ng instead")
        }

        val hcxpcapngtoolExists = rootFileExists("$CHROOT_PATH/usr/bin/hcxpcapngtool")
        Log.d(TAG, "hcxpcapngtool: exists=$hcxpcapngtoolExists")
        if (!hcxpcapngtoolExists) {
            Log.d(TAG, "hcxpcapngtool not found — PMKID/hashcat export unavailable")
        }

        val bettercapExists = rootFileExists("$CHROOT_PATH/usr/bin/bettercap")
        val bettercapExec = rootCanExecute("$CHROOT_PATH/usr/bin/bettercap")
        Log.d(TAG, "bettercap: exists=$bettercapExists, canExecute=$bettercapExec")
        if (!bettercapExists || !bettercapExec) {
            Log.w(
                TAG,
                "WARN: bettercap not found or not executable — Bettercap REST API unavailable!"
            )
        }

        return allOk
    }

    suspend fun runDiagnosticCommand(): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== RUNNING DIAGNOSTIC COMMANDS ===")
        val sb = StringBuilder()

        val diagnostics = listOf(
            "/bin/busybox hexdump -C /usr/bin/pixiewps 2>&1 | /bin/busybox head -5",
            "/bin/busybox ls -la /usr/bin/pixiewps /usr/bin/python3 /opt/PixieWps/pixie.py 2>&1",
            "/usr/bin/pixiewps 2>&1 | /bin/busybox head -20",
            "/usr/bin/pixiewps --pke AA --pkr BB --e-hash1 CC --e-hash2 DD --authkey EE --e-nonce FF 2>&1 | /bin/busybox head -20",
            "/bin/busybox which pixiewps ; /usr/bin/python3 -u /opt/PixieWps/pixie.py --help 2>&1 | /bin/busybox head -30",
            "/bin/busybox which python3 ; /usr/bin/python3 -c 'print(\"uid=\" + str(__import__(\"os\").getuid()))' 2>&1",
            "/sbin/ip link show wlan0 2>&1",
            "/usr/sbin/iw dev 2>&1"
        )

        for (diag in diagnostics) {
            sb.append("\n=== DIAGNOSTIC: $diag ===\n")
            Log.d(TAG, "Running diagnostic: $diag")
            val (stdout, stderr) = executeInChrootWithRoot(diag)
            sb.append("STDOUT: ${stdout.joinToString("\n")}\n")
            sb.append("STDERR: ${stderr.joinToString("\n")}\n")
            Log.d(
                TAG,
                "Diagnostic result: stdout=${stdout.size} lines, stderr=${stderr.size} lines"
            )
        }

        Log.d(TAG, "=== DIAGNOSTIC COMMANDS COMPLETE ===")
        sb.toString()
    }

    fun debugChrootStructure(): String {
        Log.d(TAG, "Debugging chroot structure")
        val sb = StringBuilder()
        sb.append("Chroot dir exists: ${rootDirExists(CHROOT_PATH)}\n")
        sb.append("Chroot dir path: $CHROOT_PATH\n")

        val lsResult = Shell.cmd("$BUSYBOX_PATH ls -la $CHROOT_PATH 2>&1").exec()
        sb.append("Files in chroot: ${lsResult.out.size}\n")
        lsResult.out.forEach { sb.append("  $it\n") }

        val busyboxExists = rootFileExists("$CHROOT_PATH/bin/busybox")
        val busyboxExec = rootCanExecute("$CHROOT_PATH/bin/busybox")
        sb.append("busybox exists: $busyboxExists\n")
        sb.append("busybox canExecute: $busyboxExec\n")

        val directTest = Shell.cmd("${chrootBin()} $CHROOT_PATH /bin/busybox true").exec()
        sb.append("Direct chroot test: code=${directTest.code}, success=${directTest.isSuccess}\n")
        sb.append("Direct chroot stdout: ${directTest.out}\n")
        sb.append("Direct chroot stderr: ${directTest.err}\n")

        return sb.toString()
    }

    fun getChrootPath(): String = CHROOT_PATH

    private fun buildDiagnosticResultText(result: ChrootDiagnostics.StageResult): String =
        buildString {
            val lines = result.output.lineSequence().filter { it.isNotBlank() }.toList()
            val informational = setOf(
                "selinux_status", "kernel_version", "proc_filesystems",
                "proc_mounts_noexec", "exec_directories", "context",
                "capabilities", "mount_sysfs", "busybox_linkage",
                "kernel_chroot_config", "seccomp_status", "knox_indicators",
                "chroot_sysctl", "proot_available"
            )
            when {
                result.name == "magiskpolicy" && result.exitCode != 0 -> append("not found")
                result.name == "knox_indicators" && lines.any {
                    it.contains("KNOX") || it.startsWith(
                        "v"
                    )
                } ->
                    append(lines.joinToString(", "))

                result.name in informational -> append(lines.firstOrNull()?.take(60) ?: "")
                result.exitCode != 0 -> {
                    append("exit ${result.exitCode}")
                    val info = lines.firstOrNull()?.take(60)
                    if (info != null) append(" — $info")
                }

                else -> append(lines.firstOrNull()?.take(60) ?: "OK")
            }
            if (result.avcEntries.isNotEmpty()) append(" [${result.avcEntries.size} AVC]")
        }

    suspend fun runPermissionDiagnostic(
        onStageResult: ((ChrootDiagnostics.StageResult) -> Unit)? = null
    ): List<ChrootDiagnostics.StageResult> {
        val diag = ChrootDiagnostics(BUSYBOX_PATH, CHROOT_PATH)
        return withContext(Dispatchers.IO) { diag.runDiagnostic(onStageResult) }
    }

    fun svcWifiToggle(enable: Boolean): Boolean {
        val cmd = if (enable) "svc wifi enable" else "svc wifi disable"
        Log.d(TAG, "Toggling WiFi: $cmd")

        return try {
            val process = Runtime.getRuntime().exec("su -mm")
            val stdin = process.outputStream
            val stdoutThread = Thread { process.inputStream.use { it.readBytes() } }
            val stderrThread = Thread { process.errorStream.use { it.readBytes() } }
            stdoutThread.start()
            stderrThread.start()

            stdin.write("$cmd\n".toByteArray())
            stdin.write("exit\n".toByteArray())
            stdin.close()

            stdoutThread.join(5000)
            stderrThread.join(5000)

            val exitCode = process.waitFor()
            val success = exitCode == 0
            Log.d(TAG, "WiFi toggle: $cmd → exitCode=$exitCode, success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle WiFi: ${e.message}", e)
            false
        }
    }


    suspend fun disableWifiOnHost(): Boolean = withContext(Dispatchers.IO) {
        val cmd = "svc wifi disable"
        Log.d(TAG, "Disabling WiFi on host: $cmd")
        return@withContext tryRunHostCommand(cmd)
    }

    suspend fun enableWifiOnHost(): Boolean = withContext(Dispatchers.IO) {
        val cmd = "svc wifi enable"
        Log.d(TAG, "Enabling WiFi on host: $cmd")
        return@withContext tryRunHostCommand(cmd)
    }

    private suspend fun tryRunHostCommand(cmd: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val process = Runtime.getRuntime().exec("su --mount-master")
            val stdin = process.outputStream
            val stdoutThread = Thread { process.inputStream.use { it.readBytes() } }
            val stderrThread = Thread { process.errorStream.use { it.readBytes() } }
            stdoutThread.start()
            stderrThread.start()

            stdin.write("$cmd\n".toByteArray())
            stdin.flush()
            stdin.write("exit\n".toByteArray())
            stdin.flush()
            stdin.close()

            stdoutThread.join(5000)
            stderrThread.join(5000)

            val exitCode = process.waitFor()
            val success = exitCode == 0
            Log.d(TAG, "Host command '$cmd' → exitCode=$exitCode, success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run host command: ${e.message}", e)
            false
        }
    }


    private fun cleanupFailedInstall() {
        Log.d(TAG, "Cleaning up failed installation")
        unmountChroot()
        Shell.cmd("$BUSYBOX_PATH rm -rf $CHROOT_BASE 2>/dev/null || true").exec()
        Log.d(TAG, "Cleanup completed")
    }

    fun uninstall(): Boolean {
        Log.d(TAG, "Uninstalling chroot")
        unmountChroot()
        val result = Shell.cmd("$BUSYBOX_PATH rm -rf $CHROOT_PATH").exec()
        Log.d(TAG, "Uninstall result: ${result.isSuccess}")
        return result.isSuccess
    }

    @Volatile
    private var isForceCleanupRunning = false

    fun cancelSession() {
        Log.d(TAG, "Session cancellation requested")
        sessionCancelled = true
        sessionCleaned = true
        try {
            Shell.cmd("killall -9 airodump-ng 2>/dev/null; killall -9 aireplay-ng 2>/dev/null")
                .exec()
            Log.d(TAG, "cancelSession: killall sent")
        } catch (e: Exception) {
            Log.w(TAG, "cancelSession: killall failed", e)
        }
    }

    suspend fun forceCleanup() {
        withContext(Dispatchers.IO) {
            synchronized(mountLock) {
                if (isForceCleanupRunning) {
                    Log.w(TAG, "forceCleanup already running, skipping")
                    return@withContext
                }
                if (!isChrootMounted) {
                    Log.w(TAG, "Chroot not mounted, skipping forceCleanup")
                    return@withContext
                }
                isForceCleanupRunning = true
            }

            Log.d(TAG, "=== FORCE CLEANUP START ===")
            try {

                val killResult = Shell.cmd(
                    "for pid_dir in /proc/[0-9]*; do " +
                            "if [ -L \"\$pid_dir/root\" ] && readlink \"\$pid_dir/root\" 2>/dev/null | grep -q '$CHROOT_PATH'; then " +
                            "pid_num=\$(basename \"\$pid_dir\"); " +
                            "kill -9 \$pid_num 2>/dev/null; " +
                            "fi; done"
                ).exec()
                Log.d(
                    TAG,
                    "Kill chroot processes by root dir: ${if (killResult.isSuccess) "OK" else "WARN"}"
                )


                Shell.cmd(
                    "for pid_dir in /proc/[0-9]*; do " +
                            "if [ -f \"\$pid_dir/cmdline\" ] && cat \"\$pid_dir/cmdline\" 2>/dev/null | grep -qE 'airodump-ng|aireplay-ng|aircrack-ng|hcxdumptool|cowpatty|pixiewps'; then " +
                            "pid_num=\$(basename \"\$pid_dir\"); " +
                            "current=\$pid_num; " +
                            "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do " +
                            "if [ -f \"/proc/\$current/stat\" ]; then " +
                            "ppid=\$(awk '{print \$4}' \"/proc/\$current/stat\" 2>/dev/null); " +
                            "if [ -n \"\$ppid\" ] && [ \"\$ppid\" != \"0\" ] && [ \"\$ppid\" != \"\$current\" ]; then " +
                            "if [ -f \"/proc/\$ppid/cmdline\" ] && cat \"/proc/\$ppid/cmdline\" 2>/dev/null | grep -q '$CHROOT_PATH'; then " +
                            "kill -9 \$pid_num 2>/dev/null; break; fi; " +
                            "current=\$ppid; " +
                            "else break; fi; " +
                            "else break; fi; " +
                            "done; " +
                            "fi; done"
                ).exec()

                unmountChroot()

                Shell.cmd(
                    "$BUSYBOX_PATH umount -f $CHROOT_PATH/dev/pts 2>/dev/null; " +
                            "$BUSYBOX_PATH umount -f $CHROOT_PATH/dev 2>/dev/null; " +
                            "$BUSYBOX_PATH umount -f $CHROOT_PATH/sys 2>/dev/null; " +
                            "$BUSYBOX_PATH umount -f $CHROOT_PATH/proc 2>/dev/null; " +
                            "true"
                ).exec()
                Log.d(TAG, "Force umount done")
            } catch (e: Exception) {
                Log.e(TAG, "Force cleanup failed", e)
            } finally {
                synchronized(mountLock) {
                    isForceCleanupRunning = false
                }
            }
            Log.d(TAG, "=== FORCE CLEANUP END ===")
        }
    }

}
