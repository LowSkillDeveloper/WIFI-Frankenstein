package com.lsd.wififrankenstein.ui.internetblocking.scanner

import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.TcpPingResult
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.net.Socket
import java.net.SocketTimeoutException

class TcpPingScanner {
    companion object {
        private const val TAG = "TcpPingScanner"
    }

    suspend fun pingTargets(
        targets: List<TcpPingTarget>,
        timeoutMs: Long = 5000
    ): List<TcpPingResult> {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting TCP ping for ${targets.size} targets")
            val semaphore = Semaphore(8)

            coroutineScope {
                targets.map { target ->
                    async {
                        semaphore.acquire()
                        try {
                            probe(target, timeoutMs)
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun probe(target: TcpPingTarget, timeoutMs: Long): TcpPingResult {
        Log.d(TAG, "Pinging ${target.label} (${target.ip}:${target.port})")
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(
                    java.net.InetSocketAddress(target.ip, target.port),
                    timeoutMs.toInt()
                )
                val latency = System.currentTimeMillis() - start
                Log.d(TAG, "${target.label} reachable: ${latency}ms")
                TcpPingResult(
                    ip = target.ip,
                    port = target.port,
                    label = target.label,
                    reachable = true,
                    latencyMs = latency,
                    status = CheckStatus.Ok
                )
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "${target.label} (${target.ip}:${target.port}) timeout: ${e.message}")
            TcpPingResult(
                ip = target.ip,
                port = target.port,
                label = target.label,
                reachable = false,
                latencyMs = null,
                status = CheckStatus.Timeout
            )
        } catch (e: Exception) {
            Log.e(
                TAG,
                "${target.label} (${target.ip}:${target.port}) error: ${e.javaClass.simpleName}: ${e.message}"
            )
            TcpPingResult(
                ip = target.ip,
                port = target.port,
                label = target.label,
                reachable = false,
                latencyMs = null,
                status = CheckStatus.Error
            )
        }
    }
}

data class TcpPingTarget(
    val ip: String,
    val port: Int,
    val label: String
)
