package com.lsd.wififrankenstein.ui.netprotection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.util.Log
import com.lsd.wififrankenstein.WifiApplication
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.FileReader
import java.util.concurrent.atomic.AtomicInteger

class ConnectionMonitor {

    private companion object {
        const val TAG = "ConnectionMonitor"
        const val ESTABLISHED_HEX = "01"
        const val CONNECTION_SPIKE_THRESHOLD = 2.0
        const val MIN_CONNECTIONS_FOR_SPIKE = 20
        const val TRAFFIC_SPIKE_THRESHOLD = 2_000_000
    }

    enum class DataSource { PROC_TCP, TRAFFIC_STATS, UNKNOWN }

    private val activeConnections = AtomicInteger(0)
    private var previousConnectionCount = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var onConnectionChange: ((Int) -> Unit)? = null

    private var prevRxBytes: Long = -1
    private var prevTrafficTime: Long = 0
    private var currentSource: DataSource = DataSource.UNKNOWN

    fun startMonitoring(context: Context, onChange: (Int) -> Unit) {
        onConnectionChange = onChange
        registerNetworkCallback(context)
        Log.d(TAG, "Connection monitoring started")
    }

    fun stopMonitoring(context: Context) {
        unregisterNetworkCallback(context)
        onConnectionChange = null
        Log.d(TAG, "Connection monitoring stopped")
    }

    fun getDataSource(): DataSource = currentSource

    fun checkConnectionSpike(context: Context? = null): ConnectionSpikeResult {
        val fromProc = tryReadProcTcpConnections()
        if (fromProc >= 0) {
            currentSource = DataSource.PROC_TCP
            Log.d(TAG, "ESTABLISHED connections from /proc: $fromProc")
            return analyzeConnectionCount(fromProc)
        }

        val fromShell = tryReadProcTcpViaShell()
        if (fromShell >= 0) {
            currentSource = DataSource.PROC_TCP
            Log.d(TAG, "ESTABLISHED connections from shell: $fromShell")
            return analyzeConnectionCount(fromShell)
        }

        if (context != null) {
            val trafficResult = checkTrafficSpike(context)
            if (trafficResult.spikeDetected || trafficResult.source == DataSource.TRAFFIC_STATS) {
                currentSource = DataSource.TRAFFIC_STATS
                return trafficResult
            }
        }

        currentSource = DataSource.UNKNOWN
        Log.w(TAG, "Cannot read TCP connections via any method")
        return ConnectionSpikeResult(false, activeConnections.get(), DataSource.UNKNOWN)
    }

    private fun checkTrafficSpike(context: Context): ConnectionSpikeResult {
        val currentRxBytes = try {
            val total = TrafficStats.getTotalRxBytes()
            if (total == TrafficStats.UNSUPPORTED.toLong()) null else total
        } catch (_: Exception) { null }

        if (currentRxBytes == null) return ConnectionSpikeResult(false, 0, DataSource.UNKNOWN)

        val now = System.currentTimeMillis()

        if (prevRxBytes < 0) {
            prevRxBytes = currentRxBytes
            prevTrafficTime = now
            Log.d(TAG, "TrafficStats baseline: $currentRxBytes bytes")
            return ConnectionSpikeResult(false, 0, DataSource.TRAFFIC_STATS)
        }

        val dt = (now - prevTrafficTime) / 1000.0
        if (dt < 5) return ConnectionSpikeResult(false, activeConnections.get(), DataSource.TRAFFIC_STATS)

        val bytesDelta = currentRxBytes - prevRxBytes
        prevRxBytes = currentRxBytes
        prevTrafficTime = now

        if (bytesDelta < 0) return ConnectionSpikeResult(false, activeConnections.get(), DataSource.TRAFFIC_STATS)

        val bytesPerSecond = bytesDelta / dt
        Log.d(TAG, "TrafficStats: delta=${bytesDelta}bytes rate=${bytesPerSecond.toInt()}/s")

        val isSpike = bytesPerSecond > TRAFFIC_SPIKE_THRESHOLD && dt > 10
        if (isSpike) {
            Log.w(TAG, "CONNECTION SPIKE via traffic! rate=${bytesPerSecond.toInt()}/s")
        }

        return ConnectionSpikeResult(isSpike, activeConnections.get(), DataSource.TRAFFIC_STATS)
    }

    private fun analyzeConnectionCount(currentCount: Int): ConnectionSpikeResult {
        val previous = previousConnectionCount
        previousConnectionCount = currentCount
        activeConnections.set(currentCount)

        onConnectionChange?.invoke(currentCount)

        if (previous == 0) {
            Log.d(TAG, "First connection count: $currentCount (baseline)")
            return ConnectionSpikeResult(false, currentCount, DataSource.PROC_TCP)
        }

        val growth = currentCount.toDouble() / previous
        Log.d(TAG, "Connections: prev=$previous current=$currentCount growth=${"%.1f".format(growth)}x")

        val isSpike = growth >= CONNECTION_SPIKE_THRESHOLD && currentCount > MIN_CONNECTIONS_FOR_SPIKE

        if (isSpike) {
            Log.w(TAG, "CONNECTION SPIKE DETECTED! $previous -> $currentCount (${growth}x)")
        }

        return ConnectionSpikeResult(isSpike, currentCount, DataSource.PROC_TCP)
    }

    private fun registerNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: NetworkCapabilities
                ) {
                    val count = activeConnections.get()
                    onConnectionChange?.invoke(count)
                }
            }

            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    private fun tryReadProcTcpConnections(): Int {
        return try {
            var count = 0
            count += countEstablishedInFile("/proc/net/tcp")
            count += countEstablishedInFile("/proc/net/tcp6")
            count
        } catch (e: Exception) {
            Log.d(TAG, "Cannot read /proc/net/tcp: ${e.message}")
            -1
        }
    }

    private fun countEstablishedInFile(path: String): Int {
        return try {
            var count = 0
            BufferedReader(FileReader(path)).use { reader ->
                reader.readLine()
                var line = reader.readLine()
                while (line != null) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 4 && parts[3] == ESTABLISHED_HEX) count++
                    line = reader.readLine()
                }
            }
            count
        } catch (_: Exception) {
            0
        }
    }

    private fun tryReadProcTcpViaShell(): Int {
        if (WifiApplication.isRootAvailable != true) return -1
        return try {
            val result = Shell.cmd("cat /proc/net/tcp /proc/net/tcp6").exec()
            if (!result.isSuccess) return -1
            result.out.count { line ->
                val parts = line.trim().split("\\s+".toRegex())
                parts.size >= 4 && parts[3] == ESTABLISHED_HEX
            }
        } catch (_: Exception) {
            -1
        }
    }

    data class ConnectionSpikeResult(
        val spikeDetected: Boolean,
        val currentConnections: Int,
        val source: DataSource = DataSource.UNKNOWN
    )
}
