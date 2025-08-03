package com.lsd.wififrankenstein.ui.wifianalysis

import android.net.wifi.ScanResult
import android.os.Build

data class NetworkChannelInfo(
    val scanResult: ScanResult,
    val channel: Int,
    val frequency: Int,
    val channelWidth: ChannelBandwidth,
    val startFrequency: Int,
    val endFrequency: Int,
    val band: FrequencyBand
)

data class ChannelAnalysisResult(
    val channel: Int,
    val frequency: Int,
    val band: FrequencyBand,
    val networks: List<NetworkChannelInfo>,
    val strongNetworksCount: Int,
    val interferingNetworksCount: Int,
    val utilizationPercentage: Int,
    val qualityScore: Int
)

data class OptimalChannelSuggestion(
    val channel: Int,
    val frequency: Int,
    val band: FrequencyBand,
    val qualityScore: Int,
    val reasonKey: String,
    val interferenceLevel: InterferenceLevel
)

data class NetworkEnvironmentSummary(
    val totalNetworksCount: Int,
    val distinctChannelsCount: Int,
    val meanSignalLevel: Int,
    val channelAnalyses: Map<FrequencyBand, List<ChannelAnalysisResult>>,
    val optimalSuggestions: Map<FrequencyBand, List<OptimalChannelSuggestion>>
)

enum class ChannelBandwidth(val widthMHz: Int, val spreadRadius: Int) {
    WIDTH_20(20, 10),
    WIDTH_40(40, 20),
    WIDTH_80(80, 40),
    WIDTH_160(160, 80),
    WIDTH_320(320, 160);

    companion object {
        fun fromScanResult(scanResult: ScanResult): ChannelBandwidth {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                when (scanResult.channelWidth) {
                    0 -> WIDTH_20
                    1 -> WIDTH_40
                    2 -> WIDTH_80
                    3 -> WIDTH_160
                    4 -> WIDTH_80
                    5 -> WIDTH_320
                    else -> WIDTH_20
                }
            } else {
                WIDTH_20
            }
        }
    }
}

enum class InterferenceLevel {
    NONE, LOW, MODERATE, HIGH, SEVERE
}