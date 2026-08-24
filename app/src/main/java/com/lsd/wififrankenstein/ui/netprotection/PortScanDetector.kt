package com.lsd.wififrankenstein.ui.netprotection

import android.content.Context
import android.net.TrafficStats
import android.util.Log
import com.lsd.wififrankenstein.WifiApplication
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.FileReader

class PortScanDetector {

    private companion object {
        const val TAG = "PortScanDetector"
        const val RST_SPIKE_THRESHOLD = 30
        const val INSEGS_SPIKE_THRESHOLD = 100
        const val TRAFFIC_ANOMALY_THRESHOLD = 500_000
    }

    private var prevOutRsts: Long = -1
    private var prevInSegs: Long = -1
    private var prevStatTime: Long = 0
    private var baselineReads = 0

    private var prevRxBytes: Long = -1
    private var prevTrafficTime: Long = 0

    enum class DataSource { PROC_NET_SNMP, TRAFFIC_STATS, UNKNOWN }

    private var currentSource: DataSource = DataSource.UNKNOWN

    fun checkPortScan(context: Context? = null): PortScanResult {
        val statsShell = readTcpStatsViaShell()
        if (statsShell != null) {
            currentSource = DataSource.PROC_NET_SNMP
            return analyzeTcpStats(statsShell)
        }

        val statsProc = readTcpStatsViaProc()
        if (statsProc != null) {
            currentSource = DataSource.PROC_NET_SNMP
            return analyzeTcpStats(statsProc)
        }

        if (context != null) {
            val trafficResult = checkTrafficAnomaly(context)
            if (trafficResult.detected) {
                currentSource = DataSource.TRAFFIC_STATS
                return trafficResult
            }
            if (trafficResult.source == DataSource.TRAFFIC_STATS) {
                currentSource = DataSource.TRAFFIC_STATS
                return trafficResult
            }
        }

        currentSource = DataSource.UNKNOWN
        Log.w(TAG, "Cannot read TCP stats via any method")
        return PortScanResult(false, 0, emptyList(), DataSource.UNKNOWN)
    }

    fun getDataSource(): DataSource = currentSource

    fun checkTrafficAnomaly(context: Context): PortScanResult {
        val currentRxBytes = try {
            val total = TrafficStats.getTotalRxBytes()
            if (total == TrafficStats.UNSUPPORTED.toLong()) null else total
        } catch (_: Exception) {
            null
        }

        if (currentRxBytes == null) {
            Log.d(TAG, "TrafficStats unavailable")
            return PortScanResult(false, 0, emptyList(), DataSource.UNKNOWN)
        }

        val now = System.currentTimeMillis()

        if (prevRxBytes < 0) {
            prevRxBytes = currentRxBytes
            prevTrafficTime = now
            Log.d(TAG, "TrafficStats baseline: $currentRxBytes bytes")
            return PortScanResult(false, 0, emptyList(), DataSource.TRAFFIC_STATS)
        }

        val dt = (now - prevTrafficTime) / 1000.0
        if (dt < 5) return PortScanResult(false, 0, emptyList(), DataSource.TRAFFIC_STATS)

        val bytesDelta = currentRxBytes - prevRxBytes
        prevRxBytes = currentRxBytes
        prevTrafficTime = now

        if (bytesDelta < 0) return PortScanResult(false, 0, emptyList(), DataSource.TRAFFIC_STATS)

        val bytesPerSecond = bytesDelta / dt
        Log.d(
            TAG,
            "TrafficStats: delta=${bytesDelta}bytes rate=${bytesPerSecond.toInt()}/s dt=${dt.toInt()}s"
        )

        val isAnomaly = bytesPerSecond > TRAFFIC_ANOMALY_THRESHOLD && dt > 15

        return if (isAnomaly) {
            Log.w(TAG, "TRAFFIC ANOMALY DETECTED! rate=${bytesPerSecond.toInt()}/s")
            PortScanResult(
                true,
                1,
                listOf("Traffic spike: ${bytesPerSecond.toInt()}/s"),
                DataSource.TRAFFIC_STATS
            )
        } else {
            PortScanResult(false, 0, emptyList(), DataSource.TRAFFIC_STATS)
        }
    }

    private fun analyzeTcpStats(stats: TcpStats): PortScanResult {
        val now = System.currentTimeMillis()

        if (prevOutRsts < 0) {
            prevOutRsts = stats.outRsts
            prevInSegs = stats.inSegs
            prevStatTime = now
            baselineReads++
            Log.d(
                TAG,
                "TCP stats baseline #${baselineReads}: RSTs=${stats.outRsts} InSegs=${stats.inSegs}"
            )
            return PortScanResult(false, 0, emptyList(), DataSource.PROC_NET_SNMP)
        }

        val dt = (now - prevStatTime) / 1000.0
        if (dt < 1) return PortScanResult(false, 0, emptyList(), DataSource.PROC_NET_SNMP)

        val rstDelta = stats.outRsts - prevOutRsts
        val inSegsDelta = stats.inSegs - prevInSegs

        prevOutRsts = stats.outRsts
        prevInSegs = stats.inSegs
        prevStatTime = now

        val rstRate = if (dt > 0) rstDelta / dt else 0.0
        val inSegsRate = if (dt > 0) inSegsDelta / dt else 0.0

        Log.d(
            TAG,
            "TCP stats: RSTs=${rstDelta}(${rstRate.toInt()}/s) InSegs=${inSegsDelta}(${inSegsRate.toInt()}/s)"
        )

        if (rstRate > RST_SPIKE_THRESHOLD) {
            Log.w(TAG, "PORT SCAN DETECTED! RST spike: ${rstRate.toInt()}/s")
            return PortScanResult(
                true,
                rstDelta.toInt(),
                listOf("RST spike: ${rstDelta}"),
                DataSource.PROC_NET_SNMP
            )
        }

        if (inSegsRate > INSEGS_SPIKE_THRESHOLD) {
            Log.w(TAG, "SUSPICIOUS TRAFFIC: InSegs spike ${inSegsRate.toInt()}/s")
            return PortScanResult(
                true,
                inSegsDelta.toInt(),
                listOf("InSegs spike: ${inSegsDelta}"),
                DataSource.PROC_NET_SNMP
            )
        }

        return PortScanResult(false, 0, emptyList(), DataSource.PROC_NET_SNMP)
    }

    private fun parseTcpSnmp(headerLine: String, valueLine: String): TcpStats? {
        val headers = headerLine.trim().split("\\s+".toRegex())
        val values = valueLine.trim().split("\\s+".toRegex())
        if (headers.size < 15 || values.size < 15) return null

        val inSegsIdx = headers.indexOf("InSegs")
        val outRstsIdx = headers.indexOf("OutRsts")
        val outSegsIdx = headers.indexOf("OutSegs")

        if (inSegsIdx < 0 || outRstsIdx < 0 || outSegsIdx < 0) return null

        return TcpStats(
            inSegs = values.getOrNull(inSegsIdx)?.toLongOrNull() ?: 0,
            outSegs = values.getOrNull(outSegsIdx)?.toLongOrNull() ?: 0,
            outRsts = values.getOrNull(outRstsIdx)?.toLongOrNull() ?: 0
        )
    }

    private fun readTcpStatsViaShell(): TcpStats? {
        if (WifiApplication.isRootAvailable != true) return null
        return try {
            val result = Shell.cmd("cat /proc/net/snmp").exec()
            if (!result.isSuccess) return null
            for (i in 0 until result.out.size - 1) {
                if (result.out[i].trimStart().startsWith("Tcp:")) {
                    return parseTcpSnmp(result.out[i], result.out[i + 1])
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readTcpStatsViaProc(): TcpStats? {
        return try {
            BufferedReader(FileReader("/proc/net/snmp")).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.trimStart().startsWith("Tcp:")) {
                        val valueLine = reader.readLine() ?: break
                        return parseTcpSnmp(line, valueLine)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class TcpStats(val inSegs: Long, val outSegs: Long, val outRsts: Long)

    data class PortScanResult(
        val detected: Boolean,
        val synRecvCount: Int,
        val uniqueIps: List<String>,
        val source: DataSource = DataSource.UNKNOWN
    )
}
