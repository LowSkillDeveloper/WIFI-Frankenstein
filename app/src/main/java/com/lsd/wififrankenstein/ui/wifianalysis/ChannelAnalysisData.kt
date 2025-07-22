package com.lsd.wififrankenstein.ui.wifianalysis

data class ChannelAnalysisData(
    val channel: Int,
    val frequency: Int,
    val networks: List<NetworkChannelInfo>,
    val strongSignalCount: Int,
    val overlappingNetworks: Int,
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
                overlappingNetworks = result.interferingNetworksCount,
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
    val reason: String,
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