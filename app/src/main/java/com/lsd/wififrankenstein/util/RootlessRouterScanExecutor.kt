package com.lsd.wififrankenstein.util

import android.content.Context
import com.lsd.wififrankenstein.data.RouterScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class RootlessRouterScanExecutor(
    private val context: Context,
    private val config: RuntimeConfig
) : RouterScanExecutor {

    private val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val rsCacheDir: File get() = File(context.filesDir, "rs_binaries")
    private val rsPath
        get() = if (config.type == RuntimeType.MUSL_LD) rsCacheDir.absolutePath + "/rs"
        else RouterScanUtil.RS_PATH
    private val authBasic
        get() = if (config.type == RuntimeType.MUSL_LD) rsCacheDir.absolutePath + "/auth_basic.txt"
        else RouterScanUtil.AUTH_BASIC
    private val authDigest
        get() = if (config.type == RuntimeType.MUSL_LD) rsCacheDir.absolutePath + "/auth_digest.txt"
        else RouterScanUtil.AUTH_DIGEST
    private val authForm
        get() = if (config.type == RuntimeType.MUSL_LD) rsCacheDir.absolutePath + "/auth_form.txt"
        else RouterScanUtil.AUTH_FORM

    private fun getNativeLibDir(): String? {
        return try {
            val info = context.packageManager.getApplicationInfo(context.packageName, 0)
            info.nativeLibraryDir
        } catch (e: Exception) {
            null
        }
    }

    private fun getMuslLd(): String {
        val nativeLibDir = getNativeLibDir()
        if (nativeLibDir != null) {
            val nativeFile = File(nativeLibDir, "libmusl_ld.so")
            if (nativeFile.exists()) return nativeFile.absolutePath
        }
        val extracted = File(context.filesDir, "native_libs/libmusl_ld.so")
        if (extracted.exists()) return extracted.absolutePath
        return nativeLibDir?.let { "$it/libmusl_ld.so" } ?: "/system/lib64/libmusl_ld.so"
    }

    companion object {
        private const val TAG = "RootlessRouterScan"
        private const val NMAP_TIMEOUT_MS = 30_000L
        private const val PING_BATCH_SIZE = 50

        private fun resolveLauncher(binaryPath: String, nativeLibDir: String?): List<String> {
            if (android.os.Build.VERSION.SDK_INT < 29) return listOf(binaryPath)
            if (nativeLibDir != null && binaryPath.startsWith(nativeLibDir)) return listOf(
                binaryPath
            )
            val linker = if (android.os.Build.SUPPORTED_ABIS.any { it.contains("64") }) {
                "/system/bin/linker64"
            } else {
                "/system/bin/linker"
            }
            return listOf(linker, binaryPath)
        }
    }

    private fun buildCommandPrefix(): List<String> {
        val cmdArgs = mutableListOf<String>()

        when (config.type) {
            RuntimeType.PROROOT -> {
                val nativeLibDir = getNativeLibDir() ?: ""
                val launcher = resolveLauncher("$nativeLibDir/libproroot.so", nativeLibDir)
                cmdArgs.addAll(launcher)
                cmdArgs.add("-r")
                cmdArgs.add(rootfsDir.absolutePath)
                cmdArgs.add("-0")
                if (config.useLink2Symlink) cmdArgs.add("--link2symlink")
                cmdArgs.add("-b")
                cmdArgs.add("/dev:/dev")
                cmdArgs.add("-b")
                cmdArgs.add("/sys:/sys")
                cmdArgs.add("-b")
                cmdArgs.add("/proc:/proc")
                if (config.useTmpBind) {
                    cmdArgs.add("-b")
                    cmdArgs.add("${context.cacheDir.absolutePath}:/tmp")
                }
                cmdArgs.add("-w")
                cmdArgs.add("/root")
            }

            RuntimeType.PROOT -> {
                val prootPath = "${getNativeLibDir() ?: ""}/libproot_portable.so"
                val launcher = resolveLauncher(prootPath, getNativeLibDir())
                cmdArgs.addAll(launcher)
                cmdArgs.add("-r")
                cmdArgs.add(rootfsDir.absolutePath)
                cmdArgs.add("-0")
                if (config.useLink2Symlink) cmdArgs.add("--link2symlink")
                cmdArgs.add("-b")
                cmdArgs.add("/dev/")
                cmdArgs.add("-b")
                cmdArgs.add("/sys/")
                cmdArgs.add("-b")
                cmdArgs.add("/proc/")
                if (config.useTmpBind) {
                    cmdArgs.add("-b")
                    cmdArgs.add("${context.cacheDir.absolutePath}:/tmp")
                }
                cmdArgs.add("-w")
                cmdArgs.add("/root")
            }

            RuntimeType.LINKER64 -> {
                val linker = if (android.os.Build.SUPPORTED_ABIS.any { it.contains("64") }) {
                    "/system/bin/linker64"
                } else {
                    "/system/bin/linker"
                }
                cmdArgs.add(linker)
            }

            RuntimeType.MUSL_LD -> {
                cmdArgs.add(getMuslLd())
                cmdArgs.add(rsCacheDir.absolutePath + "/rs")
            }
        }

        when (config.type) {
            RuntimeType.LINKER64 -> {
                cmdArgs.add(File(rootfsDir, rsPath).absolutePath)
            }

            else -> {
                cmdArgs.add("/bin/busybox")
                cmdArgs.add("sh")
                cmdArgs.add("-c")
                cmdArgs.add("export HOME=/root; export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export LD_LIBRARY_PATH=/opt/RouterScan:/usr/lib; exec \"$@\"")
                cmdArgs.add("--")
            }
        }

        return cmdArgs
    }

    private fun buildEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        when (config.type) {
            RuntimeType.PROROOT -> {
                env["PROROOT_TMP_DIR"] = config.tmpDir ?: context.cacheDir.absolutePath
            }

            RuntimeType.PROOT -> {
                if (config.tmpDir != null) {
                    env["PROOT_TMP_DIR"] = config.tmpDir
                }
            }

            RuntimeType.LINKER64 -> {
                env["LD_LIBRARY_PATH"] =
                    "${rootfsDir.absolutePath}/opt/RouterScan:${rootfsDir.absolutePath}/usr/lib:${rootfsDir.absolutePath}/lib"
            }

            RuntimeType.MUSL_LD -> {
                env["LD_LIBRARY_PATH"] = rsCacheDir.absolutePath
            }
        }
        return env
    }

    private suspend fun executeScanCommand(
        ip: String,
        port: String,
        timeoutMs: Long = 120_000
    ): String = withContext(Dispatchers.IO) {
        val prefix = buildCommandPrefix()
        val extraArgs = when (config.type) {
            RuntimeType.LINKER64 -> listOf(
                ip,
                File(rootfsDir, authBasic).absolutePath,
                File(rootfsDir, authDigest).absolutePath,
                File(rootfsDir, authForm).absolutePath
            )

            RuntimeType.MUSL_LD -> listOf(ip, authBasic, authDigest, authForm)
            else -> listOf(rsPath, ip, authBasic, authDigest, authForm)
        }
        val cmd = prefix + extraArgs
        val cmdStr = cmd.joinToString(" ")

        Log.d(TAG, "Executing: ${cmdStr.take(500)}")
        if (cmdStr.length > 500) {
            Log.d(TAG, "Command truncated, full length: ${cmdStr.length}")
        }

        val pb = ProcessBuilder(cmd)
            .directory(rootfsDir)
            .redirectErrorStream(true)

        val envMap = buildEnv()
        pb.environment().putAll(envMap)
        Log.d(TAG, "Env vars: $envMap")

        val startTime = System.currentTimeMillis()
        val process = pb.start()

        val output = try {
            withTimeout(timeoutMs) {
                process.inputStream.bufferedReader().readText().also {
                    process.waitFor()
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "Command timed out after ${elapsed}ms (timeout=$timeoutMs)")
            ""
        } catch (e: Exception) {
            process.destroyForcibly()
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "Command failed after ${elapsed}ms", e)
            ""
        }

        val elapsed = System.currentTimeMillis() - startTime
        val exitCode = try {
            process.exitValue()
        } catch (_: Exception) {
            -1
        }
        Log.d(
            TAG,
            "Command completed in ${elapsed}ms, exit=$exitCode, output=${output.length} chars"
        )

        if (output.isNotEmpty() && output.length <= 2000) {
            Log.d(TAG, "Output: $output")
        } else if (output.isNotEmpty()) {
            Log.d(TAG, "Output (first 500): ${output.take(500)}")
            Log.d(TAG, "Output (last 500): ${output.takeLast(500)}")
        }

        output
    }

    override suspend fun scanRouter(
        ip: String,
        port: String,
        onProgress: ((String) -> Unit)?,
        sessionTimeout: Long
    ): RouterScanResult {
        Log.d(TAG, "Scanning router: $ip:$port")
        onProgress?.invoke("[*] Scanning $ip:$port")

        return try {
            val output = executeScanCommand(
                ip, port, timeoutMs = sessionTimeout
            )

            onProgress?.invoke("[rs] Output received (${output.length} chars)")
            RouterScanUtil.parseRouterOutput(output, ip, port, output)
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            onProgress?.invoke("[-] Error: ${e.message}")
            RouterScanResult(
                ip = ip,
                port = port,
                status = "Error: ${e.message}",
                success = false,
                type = 2
            )
        }
    }

    override suspend fun scanMultipleRouters(
        ips: List<String>?,
        port: String,
        ipPortCombinations: List<Pair<String, String>>?,
        config: RouterScanConfig,
        onProgress: ((String) -> Unit)?,
        onResult: ((RouterScanResult) -> Unit)?
    ): List<RouterScanResult> = withContext(Dispatchers.IO) {
        val allCombinations =
            ipPortCombinations ?: (ips ?: emptyList()).map { Pair(it, port) }

        if (allCombinations.isEmpty()) {
            Log.d(TAG, "No targets to scan")
            return@withContext emptyList()
        }

        val scanStart = System.currentTimeMillis()
        Log.d(
            TAG,
            "=== scanMultipleRouters START: ${allCombinations.size} targets, ${config.maxThreads} threads, ${config.rsTimeout}ms timeout, pingBeforeScan=${config.pingBeforeScan} ==="
        )
        onProgress?.invoke("[*] Scanning ${allCombinations.size} targets (${config.maxThreads} threads)")

        val allUniqueIps = allCombinations.map { it.first }.distinct()
        Log.d(TAG, "Unique IPs: ${allUniqueIps.size}")

        val upIps: Set<String> = if (config.pingBeforeScan) {
            val pingStart = System.currentTimeMillis()
            val result = pingIps(allUniqueIps, onProgress)
            Log.d(
                TAG,
                "Ping sweep took ${System.currentTimeMillis() - pingStart}ms, ${result.size}/${allUniqueIps.size} hosts up"
            )
            result
        } else {
            allUniqueIps.toSet()
        }

        val liveCombinations = allCombinations.filter { it.first in upIps }
        Log.d(TAG, "Live targets: ${liveCombinations.size}/${allCombinations.size}")
        val results = mutableListOf<RouterScanResult>()
        val semaphore = Semaphore(config.maxThreads)

        coroutineScope {
            val jobs = liveCombinations.map { (ip, p) ->
                async {
                    semaphore.acquire()
                    val jobStart = System.currentTimeMillis()
                    try {
                        val output = executeScanCommand(
                            ip, p, timeoutMs = config.rsTimeout
                        )
                        val elapsed = System.currentTimeMillis() - jobStart
                        val parsed = RouterScanUtil.parseRouterOutput(output, ip, p, output)
                        synchronized(results) { results.add(parsed) }
                        onResult?.invoke(parsed)
                        if (parsed.success) {
                            Log.d(
                                TAG,
                                "[OK] $ip:$p scanned in ${elapsed}ms: SSID=${parsed.ssid}, Auth=${parsed.auth}"
                            )
                            onProgress?.invoke("[+] $ip:$p: ${parsed.ssid} (${parsed.auth})")
                        } else {
                            Log.w(TAG, "[FAIL] $ip:$p scanned in ${elapsed}ms: ${parsed.status}")
                            onProgress?.invoke("[-] $ip:$p: ${parsed.status}")
                        }
                        parsed
                    } catch (e: Exception) {
                        val elapsed = System.currentTimeMillis() - jobStart
                        Log.e(TAG, "Scan failed for $ip:$p after ${elapsed}ms", e)
                        val err = RouterScanResult(
                            ip = ip, port = p, status = "Error: ${e.message}",
                            success = false, type = 2
                        )
                        synchronized(results) { results.add(err) }
                        err
                    } finally {
                        semaphore.release()
                    }
                }
            }
            jobs.awaitAll()
        }

        val totalTime = System.currentTimeMillis() - scanStart
        val successCount = results.count { it.success }
        Log.d(
            TAG,
            "=== scanMultipleRouters DONE: ${results.size} results, $successCount success, ${results.size - successCount} failed, ${totalTime}ms ==="
        )
        results
    }

    override suspend fun checkRsBinary(): Boolean = withContext(Dispatchers.IO) {
        val rsFile = if (config.type == RuntimeType.MUSL_LD) File(rsPath)
        else File(rootfsDir, rsPath)
        val exists = rsFile.exists() && rsFile.isFile
        Log.d(TAG, "rs binary exists at ${rsFile.absolutePath}: $exists")
        exists
    }

    suspend fun pingIps(
        ips: List<String>,
        onProgress: ((String) -> Unit)?
    ): Set<String> = withContext(Dispatchers.IO) {
        val upIps = mutableSetOf<String>()

        onProgress?.invoke("[nmap] Pinging ${ips.size} hosts...")

        try {
            val batches = ips.chunked(PING_BATCH_SIZE)
            for (batch in batches) {
                val nmapArgs = listOf(
                    "/usr/bin/nmap", "-sn", "-PE", "-PP", "-PS80", "-PU80"
                ) + batch

                val prefix = buildCommandPrefix()
                val cmd = prefix + nmapArgs

                val pb = ProcessBuilder(cmd)
                    .directory(rootfsDir)
                    .redirectErrorStream(true)
                pb.environment().putAll(buildEnv())

                val process = pb.start()

                val output = try {
                    withTimeout(NMAP_TIMEOUT_MS) {
                        process.inputStream.bufferedReader().readText().also {
                            process.waitFor()
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                    Log.w(TAG, "Nmap ping batch timed out")
                    ""
                }

                val lines = output.split("\n")
                var currentIp: String? = null
                for (line in lines) {
                    val trimmed = line.trim('\r')
                    if (trimmed.startsWith("Nmap scan report for")) {
                        val rest = trimmed.substringAfter("Nmap scan report for").trim()
                        val lastCloseParen = rest.lastIndexOf(')')
                        val lastOpenParen =
                            if (lastCloseParen > 0) rest.lastIndexOf('(', lastCloseParen) else -1
                        currentIp =
                            if (lastCloseParen > 0 && lastOpenParen > 0 && lastCloseParen > lastOpenParen) {
                                rest.substring(lastOpenParen + 1, lastCloseParen).trim()
                            } else null
                    }
                    if (currentIp != null && trimmed.contains("Host is up")) {
                        upIps.add(currentIp!!)
                        onProgress?.invoke("[+] $currentIp is responding")
                        currentIp = null
                    }
                }
            }

            for (ip in ips) {
                if (ip !in upIps) {
                    onProgress?.invoke("[-] $ip is not responding")
                }
            }
            onProgress?.invoke("[+] ${upIps.size}/${ips.size} hosts are up")
        } catch (e: Exception) {
            Log.e(TAG, "Nmap ping failed", e)
            onProgress?.invoke("[-] Nmap error: ${e.message}")
        }

        upIps
    }
}
