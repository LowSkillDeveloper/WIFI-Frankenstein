package com.lsd.wififrankenstein.util

import android.content.Context
import com.lsd.wififrankenstein.data.RouterScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChrootRouterScanExecutor(private val context: Context) : RouterScanExecutor {

    private val chrootManager = ChrootManager(context)

    companion object {
        private const val TAG = "ChrootRouterScanExec"
    }

    override suspend fun scanRouter(
        ip: String,
        port: String,
        onProgress: ((String) -> Unit)?,
        sessionTimeout: Long
    ): RouterScanResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Scanning router: $ip:$port")
        onProgress?.invoke("[*] Scanning $ip:$port")

        try {
            val cmd =
                "${RouterScanUtil.RS_PATH} $ip ${RouterScanUtil.AUTH_BASIC} ${RouterScanUtil.AUTH_DIGEST} ${RouterScanUtil.AUTH_FORM}"
            Log.d(TAG, "Command: $cmd")
            onProgress?.invoke("[chroot] $cmd")

            val (stdout, stderr) = chrootManager.executeInChrootWithRoot(
                cmd,
                mapOf("LD_LIBRARY_PATH" to "/opt/RouterScan"),
                { line ->
                    Log.d(TAG, "chroot output: $line")
                    onProgress?.invoke("[rs] $line")
                },
                sessionTimeout
            )

            val combinedOutput = stdout.joinToString("\n") + "\n" + stderr.joinToString("\n")
            RouterScanUtil.parseRouterOutput(combinedOutput, ip, port, combinedOutput)
        } catch (e: CancellationException) {
            throw e
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
    ): List<RouterScanResult> {
        val allCombinations =
            ipPortCombinations ?: (ips ?: emptyList()).map { ip -> Pair(ip, port) }

        if (allCombinations.isEmpty()) {
            Log.d(TAG, "No targets to scan")
            return emptyList()
        }

        Log.d(
            TAG,
            "Scanning ${allCombinations.size} targets in batches of ${RouterScanUtil.BATCH_SIZE}"
        )
        onProgress?.invoke("[*] Scanning ${allCombinations.size} targets (${config.maxThreads} threads)")

        return chrootManager.executePersistentBatch(onProgress) { executor ->
            val results = mutableListOf<RouterScanResult>()

            val allUniqueIps = allCombinations.map { it.first }.distinct()

            val upIps: Set<String> = if (config.pingBeforeScan) {
                val nmapCmd = "nmap -sn -PE -PP -PS80 -PU80 ${allUniqueIps.joinToString(" ")}"
                onProgress?.invoke("[nmap] Pinging ${allUniqueIps.size} hosts...")
                Log.d(TAG, "Nmap command: $nmapCmd")

                val nmapOutput = executor.executeSync(nmapCmd, RouterScanUtil.NMAP_TIMEOUT)
                val parsed = RouterScanUtil.parseNmapOutput(nmapOutput)

                for (ip in allUniqueIps) {
                    if (ip in parsed) {
                        onProgress?.invoke("[+] $ip is responding")
                    } else {
                        onProgress?.invoke("[-] $ip is not responding")
                    }
                }

                onProgress?.invoke("[+] ${parsed.size}/${allUniqueIps.size} hosts are up")
                Log.d(TAG, "Up IPs: ${parsed.joinToString(", ")}")

                parsed
            } else {
                allUniqueIps.toSet()
            }

            val liveCombinations = allCombinations.filter { it.first in upIps }

            val rsBatches = liveCombinations.chunked(RouterScanUtil.BATCH_SIZE)
            for ((batchIndex, batch) in rsBatches.withIndex()) {
                onProgress?.invoke("[*] Processing rs batch ${batchIndex + 1}/${rsBatches.size} (${batch.size} targets)")
                Log.d(TAG, "=== rs Batch ${batchIndex + 1}: ${batch.size} targets ===")

                val rsCommands = batch.associate { (ip, p) ->
                    "$ip:$p" to "export LD_LIBRARY_PATH=/opt/RouterScan && ${RouterScanUtil.RS_PATH} $ip ${RouterScanUtil.AUTH_BASIC} ${RouterScanUtil.AUTH_DIGEST} ${RouterScanUtil.AUTH_FORM}"
                }

                fun handleTargetOutput(ipPort: String, fullOut: String): RouterScanResult {
                    val ip = ipPort.substringBefore(":")
                    val p = ipPort.substringAfter(":")
                    val parsed = RouterScanUtil.parseRouterOutput(fullOut, ip, p, fullOut)
                    onResult?.invoke(parsed)
                    if (parsed.success) {
                        onProgress?.invoke("[+] $ip:$p: ${parsed.ssid} (${parsed.auth})")
                    } else {
                        onProgress?.invoke("[-] $ip:$p: ${parsed.status}")
                    }
                    return parsed
                }

                val rsOutput = executor.executeParallel(
                    rsCommands,
                    config.rsTimeout,
                    config.maxThreads,
                    onTargetCompleted = { ipPort, lines ->
                        handleTargetOutput(ipPort, lines.joinToString("\n"))
                    }
                )

                val batchResults = rsOutput.map { (ipPort, output) ->
                    handleTargetOutput(ipPort, output.joinToString("\n"))
                }

                results.addAll(batchResults)
            }

            results
        }
    }

    override suspend fun checkRsBinary(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking rs binary...")
        try {
            val result = chrootManager.executeInChroot("ls -la ${RouterScanUtil.RS_PATH}")
            val exists =
                (result.code == 0) && result.out.any { it.contains(RouterScanUtil.RS_PATH) }
            Log.d(TAG, "rs binary exists: $exists")
            exists
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check rs binary", e)
            false
        }
    }
}
