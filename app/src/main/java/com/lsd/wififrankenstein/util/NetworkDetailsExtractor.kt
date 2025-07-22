package com.lsd.wififrankenstein.util

import android.net.wifi.ScanResult
import android.os.Build

data class NetworkDetails(
    val channel: Int,
    val frequencyBand: NetworkFrequencyBand,
    val bandwidth: NetworkBandwidth,
    val protocol: NetworkProtocol,
    val security: NetworkSecurityInfo,
    val frequencyMhz: Int
)

object NetworkDetailsExtractor {

    fun extractDetails(scanResult: ScanResult): NetworkDetails {
        val frequencyBand = NetworkFrequencyBand.fromFrequency(scanResult.frequency)
        val channel = NetworkFrequencyBand.getChannelNumber(scanResult.frequency, frequencyBand)
        val bandwidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NetworkBandwidth.fromScanResult(scanResult.channelWidth)
        } else {
            NetworkBandwidth.BW_20MHZ
        }
        val protocol = NetworkProtocol.fromScanResult(scanResult)
        val security = NetworkSecurityInfo(scanResult.capabilities)

        return NetworkDetails(
            channel = channel,
            frequencyBand = frequencyBand,
            bandwidth = bandwidth,
            protocol = protocol,
            security = security,
            frequencyMhz = scanResult.frequency
        )
    }
}