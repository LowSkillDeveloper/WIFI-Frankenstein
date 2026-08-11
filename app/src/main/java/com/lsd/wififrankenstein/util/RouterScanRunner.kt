package com.lsd.wififrankenstein.util

import android.content.Context
import com.lsd.wififrankenstein.data.RouterScanResult

data class RouterScanExecutionResult(
    val routers: List<RouterScanResult>,
    val rawOutput: String,
    val success: Boolean
)

data class RouterScanConfig(
    val maxThreads: Int = 10,
    val timeout: Long = 1000,
    val rsTimeout: Long = 120_000,
    val pingBeforeScan: Boolean = false
)

class RouterScanRunner(private val context: Context) {

    private val chrootManager = ChrootManager(context)
    private val rootlessManager = RootlessManager(context)

    companion object {
        private const val TAG = "RouterScanRunner"
    }

    private fun resolveExecutor(): RouterScanExecutor {
        return when (val type = chrootManager.getChrootType()) {
            is ChrootType.Root -> ChrootRouterScanExecutor(context)
            is ChrootType.RootWithoutChroot, is ChrootType.RootMissing -> {
                val config = rootlessManager.getRuntimeConfig()
                    ?: RuntimeConfig(type = RuntimeType.PROOT)
                RootlessRouterScanExecutor(context, config)
            }

            is ChrootType.Rootless -> {
                val config = rootlessManager.getRuntimeConfig()
                    ?: RuntimeConfig(type = type.rt)
                RootlessRouterScanExecutor(context, config)
            }

            ChrootType.None ->
                throw UnsupportedOperationException("Router scan not available: $type")
        }
    }

    suspend fun scanRouter(
        ip: String,
        port: String = "80",
        onProgress: ((String) -> Unit)? = null,
        sessionTimeout: Long = 120_000
    ): RouterScanResult {
        return resolveExecutor().scanRouter(ip, port, onProgress, sessionTimeout)
    }

    suspend fun scanMultipleRouters(
        ips: List<String>? = null,
        port: String = "80",
        ipPortCombinations: List<Pair<String, String>>? = null,
        config: RouterScanConfig = RouterScanConfig(),
        onProgress: ((String) -> Unit)? = null,
        onResult: ((RouterScanResult) -> Unit)? = null
    ): List<RouterScanResult> {
        return resolveExecutor().scanMultipleRouters(
            ips, port, ipPortCombinations, config, onProgress, onResult
        )
    }

    suspend fun checkRsBinary(): Boolean {
        return try {
            resolveExecutor().checkRsBinary()
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "checkRsBinary failed: ${e.message}")
            false
        }
    }
}
