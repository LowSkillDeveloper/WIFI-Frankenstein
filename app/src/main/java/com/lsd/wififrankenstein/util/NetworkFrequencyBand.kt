package com.lsd.wififrankenstein.util

import androidx.annotation.StringRes
import com.lsd.wififrankenstein.R

enum class NetworkFrequencyBand(
    @StringRes val displayNameRes: Int,
    val startFrequency: Int,
    val endFrequency: Int
) {
    BAND_2GHZ(R.string.frequency_band_2ghz, 2400, 2500),
    BAND_5GHZ(R.string.frequency_band_5ghz, 5150, 5875),
    BAND_6GHZ(R.string.frequency_band_6ghz, 5925, 7125);

    fun contains(frequency: Int): Boolean = frequency in startFrequency..endFrequency

    companion object {
        fun fromFrequency(frequency: Int): NetworkFrequencyBand =
            values().firstOrNull { it.contains(frequency) } ?: BAND_2GHZ

        fun getChannelNumber(frequency: Int, band: NetworkFrequencyBand): Int {
            return when (band) {
                BAND_2GHZ -> {
                    if (frequency in 2412..2484) {
                        when (frequency) {
                            2484 -> 14
                            else -> (frequency - 2407) / 5
                        }
                    } else 0
                }
                BAND_5GHZ -> {
                    if (frequency in 5180..5865) {
                        (frequency - 5000) / 5
                    } else 0
                }
                BAND_6GHZ -> {
                    if (frequency in 5955..7115) {
                        (frequency - 5950) / 5
                    } else 0
                }
            }
        }
    }
}