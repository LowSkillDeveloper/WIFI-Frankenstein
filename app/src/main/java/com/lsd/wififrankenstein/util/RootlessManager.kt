package com.lsd.wififrankenstein.util

import android.content.Context
import android.os.Build
import com.lsd.wififrankenstein.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class ProbeApproach(
    val type: RuntimeType,
    val useLink2Symlink: Boolean,
    val useTmpBind: Boolean,
    val tmpDir: String?
)

class RootlessManager(private val context: Context) {

    companion object {
        private const val TAG = "RootlessManager"
        private const val ROOTFS_DIR = "rootfs"
        private const val PREF_NAME = "rootless_prefs"
        private const val PREF_SETUP_DONE = "rootless_setup_done"
        private const val PREF_RT_TYPE = "runtime_type"
        private const val PREF_RT_LINK2SYMLINK = "runtime_link2symlink"
        private const val PREF_RT_TMP_BIND = "runtime_tmp_bind"
        private const val PREF_RT_TMP_DIR = "runtime_tmp_dir"
        private const val NATIVE_LIBS_DIR = "native_libs"
        private const val RS_CACHE_DIR = "rs_binaries"
        private val RS_FILES = listOf(
            "opt/RouterScan/rs",
            "opt/RouterScan/liblibrouter.so",
            "opt/RouterScan/auth_basic.txt",
            "opt/RouterScan/auth_digest.txt",
            "opt/RouterScan/auth_form.txt",
            "usr/lib/libcrypto.so.1.1"
        )
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .apply {
                if (Build.VERSION.SDK_INT < 28) {
                    try {
                        val trustAll = object : X509TrustManager {
                            override fun checkClientTrusted(
                                chain: Array<out X509Certificate>?,
                                authType: String?
                            ) {
                            }

                            override fun checkServerTrusted(
                                chain: Array<out X509Certificate>?,
                                authType: String?
                            ) {
                            }

                            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                        }
                        val ssl = SSLContext.getInstance("TLS")
                        ssl.init(null, arrayOf(trustAll), SecureRandom())
                        sslSocketFactory(ssl.socketFactory, trustAll)
                    } catch (_: Exception) {
                    }
                }
            }
            .build()
    }

    private fun getNativeLibDir(): String? {
        return try {
            val info = context.packageManager.getApplicationInfo(context.packageName, 0)
            info.nativeLibraryDir
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureNativeLibsExtracted() {
        val libDir = File(context.filesDir, NATIVE_LIBS_DIR)
        if (libDir.exists() && (libDir.listFiles()?.isNotEmpty() == true)) return
        libDir.mkdirs()
        extractLibFromApk("libbusybox.so")
        extractLibFromApk("libmusl_ld.so")
        extractLibFromApk("libproot_portable.so")
        extractLibFromApk("libproroot.so")
        extractLibFromApk("libproroot-bridge.so")
        extractLibFromApk("libproroot-linker.so")
        extractLibFromApk("libproroot-runtime.so")
        extractLibFromApk("libproroot-stub-loader.so")
    }

    private fun extractLibFromApk(libName: String) {
        val destFile = File(context.filesDir, "$NATIVE_LIBS_DIR/$libName")
        if (destFile.exists() && destFile.length() > 0) return
        try {
            ZipFile(context.applicationInfo.sourceDir).use { zip ->
                val abi = getPrimaryAbi()
                val entry = zip.getEntry("lib/$abi/$libName")
                if (entry != null) {
                    zip.getInputStream(entry).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    destFile.setExecutable(true, false)
                    Log.d(TAG, "Extracted $libName from APK to ${destFile.absolutePath}")
                } else {
                    Log.w(TAG, "$libName not found in APK for ABI $abi")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract $libName from APK", e)
        }
    }

    private fun getPrimaryAbi(): String {
        return when {
            Build.SUPPORTED_ABIS.any { it.startsWith("arm64") } -> "arm64-v8a"
            Build.SUPPORTED_ABIS.any { it.startsWith("armeabi") } -> "armeabi-v7a"
            Build.SUPPORTED_ABIS.any { it.startsWith("x86_64") } -> "x86_64"
            Build.SUPPORTED_ABIS.any { it.startsWith("x86") } -> "x86"
            else -> "arm64-v8a"
        }
    }

    fun isSupportedArchitecture(): Boolean {
        return Build.SUPPORTED_ABIS.any { abi ->
            abi.startsWith("arm64") || abi.startsWith("x86_64")
        }
    }

    fun getRuntimeConfig(): RuntimeConfig? {
        val typeName = prefs.getString(PREF_RT_TYPE, null) ?: return null
        val type = try {
            RuntimeType.valueOf(typeName)
        } catch (e: Exception) {
            return null
        }
        return RuntimeConfig(
            type = type,
            useLink2Symlink = prefs.getBoolean(PREF_RT_LINK2SYMLINK, true),
            useTmpBind = prefs.getBoolean(PREF_RT_TMP_BIND, false),
            tmpDir = prefs.getString(PREF_RT_TMP_DIR, null)
        )
    }

    private fun saveRuntimeConfig(config: RuntimeConfig) {
        prefs.edit()
            .putString(PREF_RT_TYPE, config.type.name)
            .putBoolean(PREF_RT_LINK2SYMLINK, config.useLink2Symlink)
            .putBoolean(PREF_RT_TMP_BIND, config.useTmpBind)
            .putString(PREF_RT_TMP_DIR, config.tmpDir)
            .apply()
    }

    fun isSetupCompleted(): Boolean {
        if (!isSupportedArchitecture()) return false
        val rsFile = File(context.filesDir, "$ROOTFS_DIR/${RouterScanUtil.RS_PATH}")
        return rsFile.exists() && prefs.getBoolean(PREF_SETUP_DONE, false)
    }

    private fun getProotBinary(): File {
        val nativeLibDir = getNativeLibDir()
        if (nativeLibDir != null) {
            val nativeFile = File(nativeLibDir, "libproot_portable.so")
            if (nativeFile.exists() && nativeFile.length() > 0) return nativeFile
        }
        val extracted = File(context.filesDir, "$NATIVE_LIBS_DIR/libproot_portable.so")
        if (extracted.exists() && extracted.length() > 0) return extracted
        return File(nativeLibDir ?: "", "libproot_portable.so")
    }

    fun isProotReady(): Boolean {
        val proot = getProotBinary()
        return proot.exists() && proot.length() > 0
    }

    private fun getProrootBinary(): File {
        val nativeLibDir = getNativeLibDir()
        if (nativeLibDir != null) {
            val nativeFile = File(nativeLibDir, "libproroot.so")
            if (nativeFile.exists() && nativeFile.length() > 0) return nativeFile
        }
        val extracted = File(context.filesDir, "$NATIVE_LIBS_DIR/libproroot.so")
        if (extracted.exists() && extracted.length() > 0) return extracted
        return File(nativeLibDir ?: "", "libproroot.so")
    }

    fun isProrootReady(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val nativeLibDir = getNativeLibDir()
        if (nativeLibDir != null) {
            val nativeFile = File(nativeLibDir, "libproroot.so")
            if (nativeFile.exists() && nativeFile.length() > 0) return true
        }
        val extracted = File(context.filesDir, "$NATIVE_LIBS_DIR/libproroot.so")
        return extracted.exists() && extracted.length() > 0
    }

    private fun resolveLauncher(binaryPath: String): List<String> {
        val nativeLibDir = getNativeLibDir()
        if (nativeLibDir != null && binaryPath.startsWith(nativeLibDir)) return listOf(binaryPath)
        if (Build.VERSION.SDK_INT < 29) return listOf(binaryPath)
        val linker = if (Build.SUPPORTED_ABIS.any { it.contains("64") }) {
            "/system/bin/linker64"
        } else {
            "/system/bin/linker"
        }
        return listOf(linker, binaryPath)
    }

    private fun buildProbeCommand(approach: ProbeApproach, rootfsPath: String): List<String> {
        val cmdArgs = mutableListOf<String>()

        when (approach.type) {
            RuntimeType.PROROOT -> {
                val binaryPath = getProrootBinary().absolutePath
                cmdArgs.addAll(resolveLauncher(binaryPath))
                cmdArgs.add("-r")
                cmdArgs.add(rootfsPath)
                cmdArgs.add("-0")
                if (approach.useLink2Symlink) cmdArgs.add("--link2symlink")
                cmdArgs.add("-b")
                cmdArgs.add("/dev:/dev")
                cmdArgs.add("-b")
                cmdArgs.add("/sys:/sys")
                cmdArgs.add("-b")
                cmdArgs.add("/proc:/proc")
                if (approach.useTmpBind) {
                    cmdArgs.add("-b")
                    cmdArgs.add("${context.cacheDir.absolutePath}:/tmp")
                }
                cmdArgs.add("-w")
                cmdArgs.add("/root")
            }

            RuntimeType.LINKER64 -> {
                val linker = if (Build.SUPPORTED_ABIS.any { it.contains("64") }) {
                    "/system/bin/linker64"
                } else {
                    "/system/bin/linker"
                }
                cmdArgs.add(linker)
                cmdArgs.add(File(rootfsPath, "bin/busybox").absolutePath)
                cmdArgs.add("true")
            }

            RuntimeType.PROOT -> {
                val binaryPath = getProotBinary().absolutePath
                cmdArgs.addAll(resolveLauncher(binaryPath))
                cmdArgs.add("-r")
                cmdArgs.add(rootfsPath)
                cmdArgs.add("-0")
                if (approach.useLink2Symlink) cmdArgs.add("--link2symlink")
                cmdArgs.add("-b")
                cmdArgs.add("/dev/")
                cmdArgs.add("-b")
                cmdArgs.add("/sys/")
                cmdArgs.add("-b")
                cmdArgs.add("/proc/")
                if (approach.useTmpBind) {
                    cmdArgs.add("-b")
                    cmdArgs.add("${context.cacheDir.absolutePath}:/tmp")
                }
                cmdArgs.add("-w")
                cmdArgs.add("/root")
            }

            RuntimeType.MUSL_LD -> {
                val rsFile = File(rootfsPath, "opt/RouterScan/rs")
                val rsPath = if (rsFile.exists()) rsFile.absolutePath
                else File(approach.tmpDir ?: "", "rs").absolutePath
                cmdArgs.addAll(resolveLauncher(getMuslLdBinary().absolutePath))
                cmdArgs.add(rsPath)
            }
        }

        if (approach.type != RuntimeType.LINKER64 && approach.type != RuntimeType.MUSL_LD) {
            cmdArgs.add("/bin/busybox")
            cmdArgs.add("true")
        }
        return cmdArgs
    }

    private fun buildProbeEnv(approach: ProbeApproach): Map<String, String> {
        val env = mutableMapOf<String, String>()
        val rootfsPath = File(context.filesDir, ROOTFS_DIR).absolutePath
        when (approach.type) {
            RuntimeType.PROROOT -> {
                env["PROROOT_TMP_DIR"] = approach.tmpDir ?: context.cacheDir.absolutePath
            }

            RuntimeType.PROOT -> {
                if (approach.tmpDir != null) {
                    env["PROOT_TMP_DIR"] = approach.tmpDir
                }
            }

            RuntimeType.LINKER64 -> {
                env["LD_LIBRARY_PATH"] =
                    "$rootfsPath/opt/RouterScan:$rootfsPath/usr/lib:$rootfsPath/lib"
            }

            RuntimeType.MUSL_LD -> {
                val rsDir =
                    if (approach.tmpDir != null) approach.tmpDir else "$rootfsPath/opt/RouterScan"
                env["LD_LIBRARY_PATH"] = "$rsDir:$rootfsPath/usr/lib"
            }
        }
        return env
    }

    private fun getBusyboxBinary(): File {
        val nativeLibDir = getNativeLibDir()
        if (nativeLibDir != null) {
            val nativeFile = File(nativeLibDir, "libbusybox.so")
            if (nativeFile.exists() && nativeFile.length() > 0) return nativeFile
        }
        val extracted = File(context.filesDir, "$NATIVE_LIBS_DIR/libbusybox.so")
        if (extracted.exists() && extracted.length() > 0) return extracted
        return File(nativeLibDir ?: "", "libbusybox.so")
    }

    private fun getMuslLdBinary(): File {
        val nativeLibDir = getNativeLibDir()
        if (nativeLibDir != null) {
            val nativeFile = File(nativeLibDir, "libmusl_ld.so")
            if (nativeFile.exists() && nativeFile.length() > 0) return nativeFile
        }
        val extracted = File(context.filesDir, "$NATIVE_LIBS_DIR/libmusl_ld.so")
        if (extracted.exists() && extracted.length() > 0) return extracted
        return File(nativeLibDir ?: "", "libmusl_ld.so")
    }

    fun isBusyboxReady(): Boolean {
        val busybox = getBusyboxBinary()
        return busybox.exists() && busybox.length() > 0
    }

    private fun isPieBinary(path: String): Boolean {
        return try {
            java.io.RandomAccessFile(path, "r").use { raf ->
                if (raf.length() < 18) return@use false
                raf.seek(16)
                raf.readUnsignedShort() == 3
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getRsCacheDir(): File = File(context.filesDir, RS_CACHE_DIR)

    private fun isRsCached(): Boolean {
        val cacheDir = getRsCacheDir()
        return File(cacheDir, "rs").exists() &&
                File(cacheDir, "liblibrouter.so").exists()
    }

    private fun extractRsToCache(rootfsDir: File) {
        val cacheDir = getRsCacheDir()
        cacheDir.mkdirs()
        for (relPath in RS_FILES) {
            val src = File(rootfsDir, relPath)
            if (!src.exists()) continue
            val dest = File(cacheDir, src.name)
            src.copyTo(dest, overwrite = true)
            dest.setExecutable(true, false)
        }
    }

    private fun getAllProbeApproaches(): List<ProbeApproach> {
        val approaches = mutableListOf<ProbeApproach>()
        if (isProrootReady()) {
            approaches.add(
                ProbeApproach(
                    RuntimeType.PROROOT,
                    true,
                    false,
                    context.cacheDir.absolutePath
                )
            )
            approaches.add(
                ProbeApproach(
                    RuntimeType.PROROOT,
                    false,
                    true,
                    context.cacheDir.absolutePath
                )
            )
        }
        if (isProotReady()) {
            approaches.add(
                ProbeApproach(
                    RuntimeType.PROOT,
                    true,
                    false,
                    context.cacheDir.absolutePath
                )
            )
            approaches.add(
                ProbeApproach(
                    RuntimeType.PROOT,
                    false,
                    true,
                    context.cacheDir.absolutePath
                )
            )
        }
        if (isRsCached()) {
            approaches.add(
                ProbeApproach(
                    RuntimeType.MUSL_LD,
                    false,
                    false,
                    getRsCacheDir().absolutePath
                )
            )
        }
        approaches.add(ProbeApproach(RuntimeType.LINKER64, false, false, null))
        return approaches
    }

    private suspend fun setupProbeRootfs(probeRoot: File): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Setting up probe rootfs at ${probeRoot.absolutePath}")
        ensureNativeLibsExtracted()
        try {
            val binDir = File(probeRoot, "bin")
            val libDir = File(probeRoot, "lib")
            val rootDir = File(probeRoot, "root")
            binDir.mkdirs()
            libDir.mkdirs()
            rootDir.mkdirs()

            val busyboxSrc = getBusyboxBinary()
            val muslLdSrc = getMuslLdBinary()
            if (!busyboxSrc.exists() || !muslLdSrc.exists()) {
                Log.e(TAG, "Probe binaries not found")
                return@withContext false
            }

            busyboxSrc.copyTo(File(binDir, "busybox"), overwrite = true)
            File(binDir, "busybox").setExecutable(true, false)

            muslLdSrc.copyTo(File(libDir, "ld-musl-aarch64.so.1"), overwrite = true)
            muslLdSrc.copyTo(File(libDir, "libc.musl-aarch64.so.1"), overwrite = true)

            val libc6 = File(libDir, "libc.so.6")
            if (!libc6.exists()) {
                try {
                    Files.createSymbolicLink(
                        libc6.toPath(),
                        java.nio.file.Paths.get("libc.musl-aarch64.so.1")
                    )
                } catch (e: Exception) {
                    muslLdSrc.copyTo(libc6, overwrite = true)
                }
            }

            val nativeLibsSrc = File(context.filesDir, NATIVE_LIBS_DIR)
            for (companion in listOf(
                "libproroot-linker.so", "libproroot-runtime.so",
                "libproroot-stub-loader.so", "libproroot-bridge.so"
            )) {
                val src = File(nativeLibsSrc, companion)
                if (src.exists()) {
                    val dest = File(probeRoot, companion)
                    src.copyTo(dest, overwrite = true)
                    dest.setExecutable(true, false)
                }
            }

            Log.d(
                TAG,
                "Probe rootfs ready: busybox=${busyboxSrc.length()}, musl=${muslLdSrc.length()}"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup probe rootfs", e)
            false
        }
    }

    private suspend fun runProbe(
        approaches: List<ProbeApproach>,
        rootfsPath: String,
        logLabel: String,
        onDiagnosticUpdate: ((name: String, icon: String, result: String) -> Unit)? = null
    ): Pair<RuntimeConfig?, String> = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== $logLabel: ${approaches.size} approaches ===")
        val reasons = mutableListOf<String>()
        val env = mutableMapOf<String, String>()
        if (rootfsPath != context.filesDir.absolutePath + "/$ROOTFS_DIR") {
            env["LD_LIBRARY_PATH"] = "$rootfsPath/lib"
        }

        var successConfig: RuntimeConfig? = null
        var successName: String? = null

        for ((index, approach) in approaches.withIndex()) {
            val testCmd = buildProbeCommand(approach, rootfsPath)
            val testEnv = buildProbeEnv(approach).toMutableMap().apply { putAll(env) }
            if (testEnv.containsKey("LD_LIBRARY_PATH") && env.containsKey("LD_LIBRARY_PATH")) {
                testEnv["LD_LIBRARY_PATH"] =
                    env["LD_LIBRARY_PATH"] + ":" + testEnv["LD_LIBRARY_PATH"]!!
            }

            val label = when (approach.type) {
                RuntimeType.PROROOT -> "proroot"
                RuntimeType.PROOT -> "proot"
                RuntimeType.LINKER64 -> "linker64"
                RuntimeType.MUSL_LD -> "musl_ld"
            }
            val linkLabel = if (approach.useLink2Symlink) " (+link2symlink)" else ""
            val tmpLabel = if (approach.useTmpBind) " (+tmpbind)" else ""
            val name = "$label$linkLabel$tmpLabel"

            Log.d(TAG, "Approach $index ($name) testing...")
            onDiagnosticUpdate?.invoke(name, " ⏳", "Testing...")

            try {
                val pb = ProcessBuilder(testCmd).redirectErrorStream(true)
                pb.environment().putAll(testEnv)

                val proc = pb.start()
                val completed = proc.waitFor(10, TimeUnit.SECONDS)
                val exitCode = if (completed) proc.exitValue() else -1
                val stdout = if (completed) {
                    proc.inputStream.bufferedReader().readText().take(200)
                } else {
                    proc.destroyForcibly(); proc.waitFor(1, TimeUnit.SECONDS); ""
                }

                val muslOk = approach.type == RuntimeType.MUSL_LD &&
                        completed && exitCode < 128 &&
                        stdout.contains("librouter initialized")
                if ((completed && exitCode == 0) || muslOk) {
                    if (successConfig == null) {
                        successConfig = RuntimeConfig(
                            approach.type,
                            approach.useLink2Symlink,
                            approach.useTmpBind,
                            approach.tmpDir
                        )
                        successName = name
                    }
                    onDiagnosticUpdate?.invoke(name, " ✓", "Compatible")
                    Log.d(TAG, "=== $name WORKS in $logLabel ===")
                } else {
                    val errorLine =
                        stdout.lineSequence().firstOrNull { it.isNotBlank() } ?: "unknown"
                    val summary = when {
                        exitCode == 139 -> "SIGSEGV (incompatible with kernel)"
                        exitCode == -1 -> "timed out"
                        errorLine.contains("Permission denied") -> "ptrace blocked by kernel"
                        errorLine.contains("signal 11") -> "process_vm syscalls blocked"
                        errorLine.contains("bad bind") -> "bind format error"
                        errorLine.contains("unexpected e_type") -> "linker cannot load binary (ET_EXEC)"
                        else -> "exit=$exitCode: ${errorLine.take(80)}"
                    }
                    onDiagnosticUpdate?.invoke(name, " ✗", summary)
                    reasons.add("$name: $summary")
                    Log.w(TAG, "  Approach $index FAILED in $logLabel: $summary")
                }
            } catch (e: Exception) {
                onDiagnosticUpdate?.invoke(name, " ✗", "Error: ${e.message}")
                reasons.add("approach $index: ${e.message}")
                Log.w(TAG, "  Approach $index threw: ${e.message}")
            }
        }

        if (successConfig != null) {
            Log.d(TAG, "=== $successName selected in $logLabel ===")
            Pair(successConfig!!, reasons.joinToString("\n"))
        } else {
            val summary = reasons.joinToString("\n")
            Log.e(TAG, "=== $logLabel: all ${approaches.size} failed ===\n$summary")
            Pair(null, summary)
        }
    }

    suspend fun preProbeRuntime(
        onDiagnosticUpdate: ((name: String, icon: String, result: String) -> Unit)? = null
    ): List<RuntimeType> = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== preProbeRuntime START ===")
        onDiagnosticUpdate?.invoke("Probe rootfs", " ⏳", "Setting up probe environment...")

        ensureNativeLibsExtracted()

        if (!isBusyboxReady()) {
            onDiagnosticUpdate?.invoke("Probe rootfs", " ✗", "Busybox not available")
            Log.e(TAG, "Busybox not available for pre-probe")
            return@withContext emptyList()
        }

        val probeRoot = File(context.cacheDir, "probe_rootfs")
        if (probeRoot.exists()) probeRoot.deleteRecursively()
        probeRoot.mkdirs()

        val setupOk = setupProbeRootfs(probeRoot)
        if (!setupOk) {
            probeRoot.deleteRecursively()
            onDiagnosticUpdate?.invoke("Probe rootfs", " ✗", "Failed to setup probe")
            return@withContext emptyList()
        }
        onDiagnosticUpdate?.invoke("Probe rootfs", " ✓", "Ready")

        val approaches = getAllProbeApproaches()
        if (approaches.isEmpty()) {
            Log.e(TAG, "No valid runtime binaries available")
            probeRoot.deleteRecursively()
            onDiagnosticUpdate?.invoke("Runtimes", " ✗", "No binaries available")
            return@withContext emptyList()
        }

        val (config, reasons) = runProbe(
            approaches,
            probeRoot.absolutePath,
            "preProbe",
            onDiagnosticUpdate
        )
        probeRoot.deleteRecursively()

        if (config != null) {
            Log.d(TAG, "Pre-probe selected: ${config.type}")
            listOf(config.type)
        } else {
            Log.e(TAG, "Pre-probe: no compatible runtime\n$reasons")
            onDiagnosticUpdate?.invoke("Result", " ✗", "Device incompatible. All runtimes failed.")
            emptyList()
        }
    }

    suspend fun detectRuntimeType(): RuntimeType? = withContext(Dispatchers.IO) {
        val existing = getRuntimeConfig()
        if (existing != null) {
            Log.d(TAG, "Using saved runtime config: $existing")
            return@withContext existing.type
        }

        val rootfsPath = File(context.filesDir, ROOTFS_DIR).absolutePath
        if (!File(rootfsPath, "bin/busybox").isFile) {
            Log.d(TAG, "Rootfs not extracted yet, skipping probe")
            return@withContext null
        }

        val approaches = getAllProbeApproaches()
        val (config, _) = runProbe(approaches, rootfsPath, "fullProbe")
        if (config == null) return@withContext null
        saveRuntimeConfig(config)
        config.type
    }

    suspend fun setupRootfs(
        onProgress: (Int) -> Unit,
        onStatusUpdate: (String) -> Unit,
        downloadUrl: String,
        onDiagnosticUpdate: ((name: String, icon: String, result: String) -> Unit)? = null,
        forceFullProbe: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val rootfsDir = File(context.filesDir, ROOTFS_DIR)
        Log.d(TAG, "=== setupRootfs START (forceFullProbe=$forceFullProbe) ===")
        Log.d(TAG, "Download URL: $downloadUrl")

        var candidates: List<RuntimeType>

        if (forceFullProbe) {
            Log.d(TAG, "Force full probe — skipping pre-probe")
            candidates = getAllProbeApproaches().map { it.type }.distinct()
        } else {
            onStatusUpdate(context.getString(R.string.rootless_testing_compat))
            candidates = preProbeRuntime(onDiagnosticUpdate)
            if (candidates.isEmpty()) {
                val allApproaches = getAllProbeApproaches()
                if (allApproaches.isEmpty()) {
                    onStatusUpdate(context.getString(R.string.rootless_no_binaries))
                    Log.e(TAG, "=== Pre-probe: no runtime binaries, aborting ===")
                    return@withContext false
                }
                Log.d(TAG, "Pre-probe failed but binaries exist, proceeding to full probe")
                onStatusUpdate(context.getString(R.string.rootless_preprobe_inconclusive))
                candidates = allApproaches.map { it.type }.distinct()
            } else {
                Log.d(TAG, "Pre-probe passed: $candidates")
            }
        }


        onStatusUpdate(context.getString(R.string.rootless_downloading_rootfs))
        onProgress(10)

        val cacheFile = File(context.cacheDir, "alpine-ready-rootfs.tar.gz")
        try {
            val request = Request.Builder().url(downloadUrl).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                onStatusUpdate(context.getString(R.string.rootless_download_failed_http, response.code))
                Log.e(TAG, "Download failed: HTTP ${response.code}")
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()
            Log.d(TAG, "Download content-length: $contentLength bytes")

            body.byteStream().use { input ->
                FileOutputStream(cacheFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val pct = ((totalRead * 70L / contentLength) + 10).toInt()
                            onProgress(pct.coerceAtMost(80))
                        }
                    }
                    Log.d(TAG, "Downloaded $totalRead bytes to ${cacheFile.absolutePath}")
                }
            }

            if (cacheFile.length() == 0L) {
                onStatusUpdate(context.getString(R.string.rootless_downloaded_empty))
                Log.e(TAG, "Downloaded file is empty")
                return@withContext false
            }
            Log.d(TAG, "Cache file size: ${cacheFile.length()} bytes")

            onStatusUpdate(context.getString(R.string.rootless_extracting_rootfs))
            onProgress(85)

            if (rootfsDir.exists()) {
                Log.d(TAG, "Removing existing rootfs directory for clean extraction")
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()
            val extractStart = System.currentTimeMillis()
            extractTarGz(cacheFile, rootfsDir)
            Log.d(TAG, "Extraction took ${System.currentTimeMillis() - extractStart}ms")

            val libdlExists = File(rootfsDir, "lib/libdl.so.2").exists()
            val libcExists = File(rootfsDir, "lib/libc.so.6").exists()
            Log.d(TAG, "Pre-fixup: libdl.so.2 exists=$libdlExists, libc.so.6 exists=$libcExists")

            val etcDir = File(rootfsDir, "etc")
            val resolvFile = File(etcDir, "resolv.conf")
            if (!resolvFile.exists()) {
                etcDir.mkdirs()
                resolvFile.writeText("nameserver 1.1.1.1\nnameserver 1.0.0.1\n")
                Log.d(TAG, "Created /etc/resolv.conf")
            }

            val libDlSymlink = File(rootfsDir, "lib/libdl.so.2")
            if (!libDlSymlink.exists()) {
                val libcTarget = File(rootfsDir, "lib/libc.so.6")
                Log.d(
                    TAG,
                    "libdl.so.2 missing, creating via symlink or copy from ${libcTarget.absolutePath}"
                )
                try {
                    Files.createSymbolicLink(
                        libDlSymlink.toPath(),
                        java.nio.file.Paths.get("libc.so.6")
                    )
                    Log.d(TAG, "Created libdl.so.2 symlink -> libc.so.6")
                } catch (e: NoSuchMethodError) {
                    Log.w(TAG, "NIO not available (API < 26), copying libc.so.6 to libdl.so.2")
                    if (libcTarget.exists()) {
                        libcTarget.copyTo(libDlSymlink, overwrite = true)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create libdl.so.2 symlink, copying instead", e)
                    try {
                        if (libcTarget.exists()) {
                            libcTarget.copyTo(libDlSymlink, overwrite = false)
                            val copyOk = libDlSymlink.exists() && libDlSymlink.length() > 0
                            Log.d(
                                TAG,
                                "Copied libc.so.6 to libdl.so.2 as fallback: ok=$copyOk, size=${libDlSymlink.length()}"
                            )
                        } else {
                            Log.e(
                                TAG,
                                "libc.so.6 target does not exist at ${libcTarget.absolutePath}"
                            )
                        }
                    } catch (e2: Exception) {
                        Log.e(TAG, "libdl.so.2 fallback copy also failed", e2)
                    }
                }
            } else {
                Log.d(TAG, "libdl.so.2 already exists, skipping fixup")
            }

            File(rootfsDir, "lib/ld-musl-aarch64.so.1").setExecutable(true, false)

            val rsFile = File(rootfsDir, RouterScanUtil.RS_PATH)
            val libFile = File(
                rootfsDir,
                "${RouterScanUtil.RS_PATH.substringBeforeLast("/")}/liblibrouter.so"
            )
            val nmapFile = File(rootfsDir, "usr/bin/nmap")
            val libcryptoFile = File(rootfsDir, "usr/lib/libcrypto.so.1.1")

            Log.d(
                TAG,
                "Verifying: rs=${rsFile.exists()} (${rsFile.length()}B), liblibrouter=${libFile.exists()} (${libFile.length()}B), nmap=${nmapFile.exists()}, libcrypto=${libcryptoFile.exists()}"
            )

            val allExist =
                rsFile.exists() && libFile.exists() && nmapFile.exists() && libcryptoFile.exists()
            if (!allExist) {
                onStatusUpdate(context.getString(R.string.rootless_verification_failed))
                Log.e(
                    TAG,
                    "VERIFICATION FAILED: rs=${rsFile.exists()}, liblibrouter=${libFile.exists()}, nmap=${nmapFile.exists()}, libcrypto=${libcryptoFile.exists()}"
                )
                if (rootfsDir.exists()) {
                    rootfsDir.deleteRecursively()
                    Log.d(TAG, "Rootfs deleted due to verification failure")
                }
                return@withContext false
            }


            val rsInRootfs = File(rootfsDir, "opt/RouterScan/rs").exists()
            if (rsInRootfs) {
                extractRsToCache(rootfsDir)
                Log.d(TAG, "rs binaries cached to ${getRsCacheDir().absolutePath}")
            }


            val fullProbeCandidates = if (rsInRootfs && RuntimeType.MUSL_LD !in candidates)
                candidates + RuntimeType.MUSL_LD else candidates


            val allApproaches = getAllProbeApproaches()
            val filteredApproaches = allApproaches.filter { it.type in fullProbeCandidates }

            val detectStart = System.currentTimeMillis()
            val (config, probeReason) = if (filteredApproaches.isNotEmpty()) {
                runProbe(
                    filteredApproaches,
                    rootfsDir.absolutePath,
                    "fullProbe",
                    onDiagnosticUpdate
                )
            } else {
                Log.w(
                    TAG,
                    "No filtered approaches, using first candidate: ${fullProbeCandidates.first()}"
                )
                val primary = fullProbeCandidates.first()
                val fallback = allApproaches.firstOrNull { it.type == primary }
                if (fallback != null) Pair(
                    RuntimeConfig(
                        fallback.type,
                        fallback.useLink2Symlink,
                        fallback.useTmpBind,
                        fallback.tmpDir
                    ), ""
                ) else Pair(null, "No approaches available")
            }
            Log.d(TAG, "Probe took ${System.currentTimeMillis() - detectStart}ms, result=$config")

            if (config == null) {
                val msg = if (probeReason.isNotBlank()) {
                    context.getString(R.string.rootless_not_supported, probeReason)
                } else {
                    context.getString(R.string.rootless_no_compatible_runtime)
                }
                onStatusUpdate(msg)
                Log.e(TAG, "All runtime approaches failed after rootfs setup, cleaning up rootfs")
                if (rootfsDir.exists()) {
                    rootfsDir.deleteRecursively()
                    Log.d(TAG, "Rootfs deleted due to probe failure")
                }
                return@withContext false
            }

            saveRuntimeConfig(config)
            Log.d(TAG, "Saved runtime config: $config")


            if (config.type == RuntimeType.MUSL_LD && rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
                Log.d(TAG, "Rootfs deleted, using cached rs binaries")
            }

            prefs.edit()
                .putBoolean(PREF_SETUP_DONE, true)
                .apply()
            onProgress(100)
            onStatusUpdate(context.getString(R.string.rootless_rootfs_ready))
            Log.d(
                TAG,
                "=== setupRootfs SUCCESS (${System.currentTimeMillis() - startTime}ms, config=$config) ==="
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs setup failed after ${System.currentTimeMillis() - startTime}ms", e)
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
                Log.d(TAG, "Rootfs deleted due to exception")
            }
            onStatusUpdate(context.getString(R.string.rootless_setup_failed, e.message))
            false
        } finally {
            cacheFile.delete()
            Log.d(TAG, "Cache file deleted")
        }
    }

    private fun resolveLinkTarget(linkName: String, entryName: String, destination: File): File {
        return when {
            linkName.startsWith("/") -> File(destination, linkName.removePrefix("/"))
            else -> {
                val parent = File(destination, entryName).parentFile ?: destination
                File(parent, linkName).normalize()
            }
        }
    }

    private fun extractTarGz(archive: File, destination: File) {
        try {
            extractTarGzLib(archive, destination)
        } catch (e: NoClassDefFoundError) {
            Log.w(TAG, "commons-compress not available (API < 26), using fallback parser", e)
            extractTarGzFallback(archive, destination)
        }
    }

    private fun extractTarGzLib(archive: File, destination: File) {
        Log.d(TAG, "=== extractTarGz START ===")
        Log.d(TAG, "Archive: ${archive.absolutePath} (${archive.length()}B)")
        Log.d(TAG, "Destination: ${destination.absolutePath}")
        var totalEntries = 0
        var dirs = 0
        var files = 0
        var symlinks = 0
        var symlinkFails = 0
        var symlinkCopies = 0
        val startTime = System.currentTimeMillis()

        GzipCompressorInputStream(archive.inputStream()).use { gzIn ->
            TarArchiveInputStream(gzIn).use { tarIn ->
                var entry: TarArchiveEntry? = tarIn.nextTarEntry
                val buffer = ByteArray(8192)
                while (entry != null) {
                    totalEntries++
                    val outputFile = File(destination, entry.name)
                    when {
                        entry.isDirectory -> {
                            outputFile.mkdirs()
                            dirs++
                        }

                        entry.isSymbolicLink -> {
                            symlinks++
                            if (outputFile.exists()) {
                                Log.d(TAG, "Symlink target already exists, skipping: ${entry.name}")
                                entry = tarIn.nextTarEntry
                                continue
                            }
                            outputFile.parentFile?.mkdirs()
                            try {
                                val linkTarget = File(entry.linkName)
                                Files.createSymbolicLink(outputFile.toPath(), linkTarget.toPath())
                            } catch (e: Exception) {
                                symlinkFails++
                                Log.w(
                                    TAG,
                                    "Symlink failed for ${entry.name} -> ${entry.linkName}, trying copy",
                                    e
                                )
                                try {
                                    val targetPath =
                                        resolveLinkTarget(entry.linkName, entry.name, destination)
                                    if (targetPath.exists()) {
                                        if (targetPath.isFile) {
                                            targetPath.copyTo(outputFile, overwrite = false)
                                            outputFile.setExecutable(targetPath.canExecute(), false)
                                            symlinkCopies++
                                        } else if (targetPath.isDirectory) {
                                            Log.w(
                                                TAG,
                                                "Skipping directory symlink ${entry.name} -> ${entry.linkName}"
                                            )
                                        }
                                    } else {
                                        Log.e(
                                            TAG,
                                            "Cannot resolve symlink target ${entry.linkName} for ${entry.name} (resolved to ${targetPath.absolutePath})"
                                        )
                                    }
                                } catch (e2: Exception) {
                                    Log.e(
                                        TAG,
                                        "Symlink fallback copy also failed for ${entry.name}",
                                        e2
                                    )
                                }
                            }
                        }

                        else -> {
                            files++
                            outputFile.parentFile?.mkdirs()
                            FileOutputStream(outputFile).use { out ->
                                var bytesRead: Int
                                while (tarIn.read(buffer).also { bytesRead = it } != -1) {
                                    out.write(buffer, 0, bytesRead)
                                }
                            }
                            if (entry.mode and 64 != 0 || entry.name.contains("/bin/") || entry.name.contains(
                                    "/opt/"
                                )
                            ) {
                                outputFile.setExecutable(true, false)
                            }
                        }
                    }
                    entry = tarIn.nextTarEntry
                }
            }
        }
        Log.d(
            TAG,
            "=== extractTarGz DONE (${System.currentTimeMillis() - startTime}ms, entries=$totalEntries, dirs=$dirs, files=$files, symlinks=$symlinks, symlinkFails=$symlinkFails, symlinkCopies=$symlinkCopies) ==="
        )
    }

    private fun extractTarGzFallback(archive: File, destination: File) {
        Log.d(TAG, "=== extractTarGzFallback START ===")
        Log.d(TAG, "Archive: ${archive.absolutePath} (${archive.length()}B)")
        val startTime = System.currentTimeMillis()
        val hdr = ByteArray(512)
        var totalEntries = 0
        var dirs = 0
        var files = 0

        java.util.zip.GZIPInputStream(archive.inputStream()).use { gzip ->
            while (true) {
                fillBuffer(gzip, hdr)
                if (isEndOfArchive(hdr)) break

                val name = readString(hdr, 0, 100)
                val sizeStr = readString(hdr, 124, 12)
                val typeFlag = hdr[156].toInt().toChar()
                val linkName = readString(hdr, 157, 100)
                val mode = readString(hdr, 100, 8).toIntOrNull(8) ?: 0

                if (typeFlag == 'L') {
                    val longSize = sizeStr.toLongOrNull(8) ?: 0L
                    val nameBytes = ByteArray(longSize.toInt())
                    readExact(gzip, nameBytes, longSize.toInt())
                    skipPadding(gzip, longSize)
                    fillBuffer(gzip, hdr)
                    val realSizeStr = readString(hdr, 124, 12)
                    val realSize = realSizeStr.toLongOrNull(8) ?: 0L
                    readExact(gzip, ByteArray(realSize.toInt()), realSize.toInt())
                    skipPadding(gzip, realSize)
                    continue
                }

                val size = sizeStr.toLongOrNull(8) ?: 0L
                val outputFile = File(destination, name)

                when (typeFlag) {
                    '\u0000', '0' -> {
                        files++
                        outputFile.parentFile?.mkdirs()
                        var remaining = size
                        val buf = ByteArray(8192)
                        FileOutputStream(outputFile).use { out ->
                            while (remaining > 0) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val n = gzip.read(buf, 0, toRead)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        if (mode and 64 != 0 || name.contains("/bin/") || name.contains("/opt/") || name.contains(
                                "/ld-"
                            )
                        ) {
                            outputFile.setExecutable(true, false)
                        }
                        skipPadding(gzip, size)
                    }

                    '5' -> {
                        dirs++
                        outputFile.mkdirs()
                    }

                    '2' -> {
                        Log.d(TAG, "Skipping symlink (fallback): $name -> $linkName")
                        skipPadding(gzip, size)
                    }

                    else -> {
                        skipPadding(gzip, size)
                    }
                }
                totalEntries++
            }
        }
        Log.d(
            TAG,
            "=== extractTarGzFallback DONE (${System.currentTimeMillis() - startTime}ms, entries=$totalEntries, dirs=$dirs, files=$files) ==="
        )
    }

    private fun fillBuffer(input: java.io.InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw java.io.EOFException("Unexpected end of tar stream")
            offset += n
        }
    }

    private fun readString(buf: ByteArray, offset: Int, maxLen: Int): String {
        val sb = StringBuilder()
        for (i in offset until offset + maxLen) {
            val c = buf[i].toInt().toChar()
            if (c == '\u0000') break
            sb.append(c)
        }
        return sb.toString()
    }

    private fun isEndOfArchive(buf: ByteArray): Boolean {
        return buf.all { it == 0.toByte() }
    }

    private fun readExact(input: java.io.InputStream, buf: ByteArray, len: Int) {
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            if (n < 0) break
            offset += n
        }
    }

    private fun skipPadding(input: java.io.InputStream, bytesRead: Long) {
        val remainder = bytesRead % 512
        if (remainder == 0L) return
        val padding = 512 - remainder
        var skipped = 0L
        while (skipped < padding) {
            val n = input.read(ByteArray(512), 0, minOf(512, (padding - skipped).toInt()))
            if (n < 0) break
            skipped += n
        }
    }

    fun resetSetup() {
        prefs.edit().clear().apply()
        val rootfsDir = File(context.filesDir, ROOTFS_DIR)
        rootfsDir.deleteRecursively()
    }
}
