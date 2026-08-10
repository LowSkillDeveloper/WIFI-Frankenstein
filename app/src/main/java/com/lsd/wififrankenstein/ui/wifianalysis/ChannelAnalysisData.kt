package com.lsd.wififrankenstein.ui.wifianalysis

import com.lsd.wififrankenstein.R

data class ChannelAnalysisData(
    val channel: Int,
    val frequency: Int,
    val networks: List<NetworkChannelInfo>,
    val strongSignalCount: Int,
    val interferingNetworks: List<NetworkChannelInfo>,
    val channelLoad: Int,
    val band: FrequencyBand,
    val qualityScore: Int
) {
    companion object {
        fun fromAnalysisResult(result: ChannelAnalysisResult): ChannelAnalysisData {
            return ChannelAnalysisData(
                channel = result.channel,
                frequency = result.frequency,
                networks = result.networks,
                strongSignalCount = result.strongNetworksCount,
                interferingNetworks = result.interferingNetworks,
                channelLoad = result.utilizationPercentage,
                band = result.band,
                qualityScore = result.qualityScore
            )
        }
    }
}

data class ChannelRecommendation(
    val channel: Int,
    val frequency: Int,
    val score: Int,
    val reason: RecommendationReason,
    val band: FrequencyBand,
    val interferenceLevel: InterferenceLevel
) {
    companion object {
        fun fromOptimalSuggestion(suggestion: OptimalChannelSuggestion): ChannelRecommendation {
            return ChannelRecommendation(
                channel = suggestion.channel,
                frequency = suggestion.frequency,
                score = suggestion.qualityScore,
                reason = suggestion.reasonKey,
                band = suggestion.band,
                interferenceLevel = suggestion.interferenceLevel
            )
        }
    }
}

data class WiFiEnvironmentAnalysis(
    val totalNetworks: Int,
    val uniqueChannels: Int,
    val averageSignalStrength: Int,
    val channelAnalysis: Map<FrequencyBand, List<ChannelAnalysisData>>,
    val recommendations: Map<FrequencyBand, List<ChannelRecommendation>>
) {
    companion object {
        fun fromNetworkSummary(summary: NetworkEnvironmentSummary): WiFiEnvironmentAnalysis {
            val channelData = summary.channelAnalyses.mapValues { (_, analyses) ->
                analyses.map { ChannelAnalysisData.fromAnalysisResult(it) }
            }

            val recommendations = summary.optimalSuggestions.mapValues { (_, suggestions) ->
                suggestions.map { ChannelRecommendation.fromOptimalSuggestion(it) }
            }

            return WiFiEnvironmentAnalysis(
                totalNetworks = summary.totalNetworksCount,
                uniqueChannels = summary.distinctChannelsCount,
                averageSignalStrength = summary.meanSignalLevel,
                channelAnalysis = channelData,
                recommendations = recommendations
            )
        }
    }
}

enum class FrequencyBand(val displayName: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz")
}

sealed class RecommendationReason {
    object NoInterference : RecommendationReason()
    object LightUsage : RecommendationReason()
    object LeastCongested : RecommendationReason()
    object NoNetworks : RecommendationReason()
    object NoChannelOverlap : RecommendationReason()
    object GoodChoice : RecommendationReason()
    object Acceptable : RecommendationReason()

    fun toResource(): Int {
        return when (this) {
            is NoInterference -> R.string.no_interference
            is LightUsage -> R.string.light_usage
            is LeastCongested -> R.string.least_congested
            is NoNetworks -> R.string.no_networks_on_channel
            is NoChannelOverlap -> R.string.no_channel_overlap
            is GoodChoice -> R.string.good_choice
            is Acceptable -> R.string.acceptable
        }
    }
}