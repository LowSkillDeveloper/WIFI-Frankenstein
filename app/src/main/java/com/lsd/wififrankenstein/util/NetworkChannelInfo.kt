package com.lsd.wififrankenstein.util

data class NetworkChannelInfo(
    val channelNumber: Int = 0,
    val centerFrequency: Int = 0
) : Comparable<NetworkChannelInfo> {

    fun isInRange(freq: Int): Boolean =
        freq in centerFrequency - FREQUENCY_TOLERANCE..centerFrequency + FREQUENCY_TOLERANCE

    override fun compareTo(other: NetworkChannelInfo): Int =
        compareBy<NetworkChannelInfo> { it.channelNumber }
            .thenBy { it.centerFrequency }
            .compare(this, other)

    companion object {
        private const val FREQUENCY_TOLERANCE = 10
        val UNKNOWN = NetworkChannelInfo()
    }
}