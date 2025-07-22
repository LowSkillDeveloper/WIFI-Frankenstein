package com.lsd.wififrankenstein.ui.wifianalysis

import android.net.wifi.ScanResult
import kotlin.math.abs

object NetworkFrequencyAnalyzer {

    private const val STRONG_SIGNAL_THRESHOLD_DBM = -50
    private const val CHANNEL_OVERLAP_THRESHOLD_2GHZ = 5
    private const val CHANNEL_OVERLAP_THRESHOLD_5GHZ = 20

    private val CHANNEL_2GHZ_MAP = mapOf(
        1 to 2412, 2 to 2417, 3 to 2422, 4 to 2427, 5 to 2432, 6 to 2437,
        7 to 2442, 8 to 2447, 9 to 2452, 10 to 2457, 11 to 2462, 12 to 2467,
        13 to 2472, 14 to 2484
    )

    fun analyzeNetworkEnvironment(scanResults: List<ScanResult>): NetworkEnvironmentSummary {
        val networksByBand = categorizeNetworksByBand(scanResults)
        val channelAnalysesByBand = mutableMapOf<FrequencyBand, List<ChannelAnalysisResult>>()
        val suggestionsByBand = mutableMapOf<FrequencyBand, List<OptimalChannelSuggestion>>()

        FrequencyBand.values().forEach { band ->
            val networksInBand = networksByBand[band] ?: emptyList()
            val analyses = performChannelAnalysisForBand(networksInBand, band)
            val suggestions = generateOptimalSuggestions(analyses, band)

            channelAnalysesByBand[band] = analyses
            suggestionsByBand[band] = suggestions
        }

        val distinctChannels = scanResults.map {
            determineChannelFromFrequency(it.frequency, determineFrequencyBand(it.frequency))
        }.distinct().size

        val meanSignal = if (scanResults.isNotEmpty()) {
            scanResults.map { it.level }.average().toInt()
        } else 0

        return NetworkEnvironmentSummary(
            totalNetworksCount = scanResults.size,
            distinctChannelsCount = distinctChannels,
            meanSignalLevel = meanSignal,
            channelAnalyses = channelAnalysesByBand,
            optimalSuggestions = suggestionsByBand
        )
    }

    private fun categorizeNetworksByBand(scanResults: List<ScanResult>): Map<FrequencyBand, List<NetworkChannelInfo>> {
        return scanResults.mapNotNull { result ->
            val band = determineFrequencyBand(result.frequency)
            val channel = determineChannelFromFrequency(result.frequency, band)
            if (channel != -1) {
                val bandwidth = ChannelBandwidth.fromScanResult(result)
                val startFreq = calculateStartFrequency(result.frequency, bandwidth, band)
                val endFreq = calculateEndFrequency(result.frequency, bandwidth, band)

                NetworkChannelInfo(
                    scanResult = result,
                    channel = channel,
                    frequency = result.frequency,
                    channelWidth = bandwidth,
                    startFrequency = startFreq,
                    endFrequency = endFreq,
                    band = band
                )
            } else null
        }.groupBy { it.band }
    }

    private fun performChannelAnalysisForBand(
        networks: List<NetworkChannelInfo>,
        band: FrequencyBand
    ): List<ChannelAnalysisResult> {
        val channelGroups = networks.groupBy { it.channel }
        val availableChannels = getAvailableChannelsForBand(band)

        return availableChannels.map { channelNum ->
            val channelNetworks = channelGroups[channelNum] ?: emptyList()
            val frequency = getFrequencyForChannel(channelNum, band)
            val strongCount = channelNetworks.count { it.scanResult.level >= STRONG_SIGNAL_THRESHOLD_DBM }
            val interferingCount = calculateInterferingNetworks(channelNum, band, networks)
            val utilization = calculateChannelUtilization(channelNetworks, networks.size)
            val quality = calculateQualityScore(channelNetworks.size, strongCount, interferingCount)

            ChannelAnalysisResult(
                channel = channelNum,
                frequency = frequency,
                band = band,
                networks = channelNetworks,
                strongNetworksCount = strongCount,
                interferingNetworksCount = interferingCount,
                utilizationPercentage = utilization,
                qualityScore = quality
            )
        }.sortedBy { it.channel }
    }

    private fun calculateInterferingNetworks(
        targetChannel: Int,
        band: FrequencyBand,
        allNetworks: List<NetworkChannelInfo>
    ): Int {
        return when (band) {
            FrequencyBand.GHZ_2_4 -> {
                allNetworks.count { network ->
                    network.channel != targetChannel &&
                            abs(network.channel - targetChannel) <= CHANNEL_OVERLAP_THRESHOLD_2GHZ &&
                            networksOverlap2GHz(targetChannel, network.channel)
                }
            }
            FrequencyBand.GHZ_5, FrequencyBand.GHZ_6 -> {
                allNetworks.count { network ->
                    network.channel != targetChannel &&
                            networksOverlap5GHz(targetChannel, network.channel, network.channelWidth)
                }
            }
        }
    }

    private fun networksOverlap2GHz(channel1: Int, channel2: Int): Boolean {
        return abs(channel1 - channel2) <= 4
    }

    private fun networksOverlap5GHz(channel1: Int, channel2: Int, bandwidth: ChannelBandwidth): Boolean {
        val overlap = when (bandwidth) {
            ChannelBandwidth.WIDTH_20 -> 4
            ChannelBandwidth.WIDTH_40 -> 8
            ChannelBandwidth.WIDTH_80 -> 16
            ChannelBandwidth.WIDTH_160 -> 32
            ChannelBandwidth.WIDTH_320 -> 64
        }
        return abs(channel1 - channel2) <= overlap
    }

    private fun calculateChannelUtilization(channelNetworks: List<NetworkChannelInfo>, totalNetworks: Int): Int {
        if (totalNetworks == 0) return 0
        return ((channelNetworks.size.toFloat() / totalNetworks) * 100).toInt()
    }

    private fun calculateQualityScore(networkCount: Int, strongCount: Int, interferingCount: Int): Int {
        var score = 100
        score -= networkCount * 15
        score -= strongCount * 10
        score -= interferingCount * 5
        return maxOf(0, score)
    }

    private fun generateOptimalSuggestions(
        analyses: List<ChannelAnalysisResult>,
        band: FrequencyBand
    ): List<OptimalChannelSuggestion> {
        val preferredChannels = when (band) {
            FrequencyBand.GHZ_2_4 -> listOf(1, 6, 11)
            FrequencyBand.GHZ_5 -> listOf(36, 40, 44, 48, 149, 153, 157, 161)
            FrequencyBand.GHZ_6 -> listOf(1, 5, 9, 13, 17, 21, 25, 29)
        }

        return preferredChannels.mapNotNull { channel ->
            analyses.find { it.channel == channel }?.let { analysis ->
                OptimalChannelSuggestion(
                    channel = channel,
                    frequency = analysis.frequency,
                    band = band,
                    qualityScore = analysis.qualityScore,
                    reasonKey = determineRecommendationReason(analysis),
                    interferenceLevel = determineInterferenceLevel(analysis)
                )
            }
        }.sortedByDescending { it.qualityScore }.take(3)
    }

    private fun determineRecommendationReason(analysis: ChannelAnalysisResult): String {
        return when {
            analysis.networks.isEmpty() -> "no_interference"
            analysis.networks.size == 1 && analysis.interferingNetworksCount == 0 -> "light_usage"
            analysis.interferingNetworksCount == 0 -> "no_channel_overlap"
            analysis.qualityScore >= 70 -> "good_choice"
            analysis.qualityScore >= 50 -> "acceptable"
            else -> "least_congested"
        }
    }

    private fun determineInterferenceLevel(analysis: ChannelAnalysisResult): InterferenceLevel {
        return when {
            analysis.interferingNetworksCount == 0 -> InterferenceLevel.NONE
            analysis.interferingNetworksCount <= 2 -> InterferenceLevel.LOW
            analysis.interferingNetworksCount <= 5 -> InterferenceLevel.MODERATE
            analysis.interferingNetworksCount <= 8 -> InterferenceLevel.HIGH
            else -> InterferenceLevel.SEVERE
        }
    }

    fun determineFrequencyBand(frequency: Int): FrequencyBand {
        return when {
            frequency in 2400..2500 -> FrequencyBand.GHZ_2_4
            frequency in 5000..6000 -> FrequencyBand.GHZ_5
            frequency in 6000..7000 -> FrequencyBand.GHZ_6
            else -> FrequencyBand.GHZ_2_4
        }
    }

    fun determineChannelFromFrequency(frequency: Int, band: FrequencyBand): Int {
        return when (band) {
            FrequencyBand.GHZ_2_4 -> {
                CHANNEL_2GHZ_MAP.entries.find { it.value == frequency }?.key ?: -1
            }
            FrequencyBand.GHZ_5 -> {
                when {
                    frequency in 5170..5320 -> ((frequency - 5000) / 5)
                    frequency in 5500..5700 -> (100 + (frequency - 5500) / 5)
                    frequency in 5745..5825 -> (149 + (frequency - 5745) / 5)
                    else -> -1
                }
            }
            FrequencyBand.GHZ_6 -> {
                ((frequency - 5955) / 20) + 1
            }
        }
    }

    private fun getFrequencyForChannel(channel: Int, band: FrequencyBand): Int {
        return when (band) {
            FrequencyBand.GHZ_2_4 -> CHANNEL_2GHZ_MAP[channel] ?: 0
            FrequencyBand.GHZ_5 -> {
                when {
                    channel in 1..64 -> 5000 + channel * 5
                    channel in 100..140 -> 5500 + (channel - 100) * 5
                    channel in 149..165 -> 5745 + (channel - 149) * 5
                    else -> 0
                }
            }
            FrequencyBand.GHZ_6 -> 5955 + (channel - 1) * 20
        }
    }

    private fun getAvailableChannelsForBand(band: FrequencyBand): List<Int> {
        return when (band) {
            FrequencyBand.GHZ_2_4 -> (1..14).toList()
            FrequencyBand.GHZ_5 -> listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
            FrequencyBand.GHZ_6 -> (1..29).toList()
        }
    }

    private fun calculateStartFrequency(centerFreq: Int, bandwidth: ChannelBandwidth, band: FrequencyBand): Int {
        return when (band) {
            FrequencyBand.GHZ_2_4 -> {
                when (bandwidth) {
                    ChannelBandwidth.WIDTH_40 -> centerFreq - 20
                    else -> centerFreq - 10
                }
            }
            else -> centerFreq - bandwidth.spreadRadius
        }
    }

    private fun calculateEndFrequency(centerFreq: Int, bandwidth: ChannelBandwidth, band: FrequencyBand): Int {
        return when (band) {
            FrequencyBand.GHZ_2_4 -> {
                when (bandwidth) {
                    ChannelBandwidth.WIDTH_40 -> centerFreq + 20
                    else -> centerFreq + 10
                }
            }
            else -> centerFreq + bandwidth.spreadRadius
        }
    }
}