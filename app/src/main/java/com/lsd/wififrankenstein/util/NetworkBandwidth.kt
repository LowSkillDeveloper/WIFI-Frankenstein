package com.lsd.wififrankenstein.util

import android.net.wifi.ScanResult
import androidx.annotation.StringRes
import com.lsd.wififrankenstein.R

enum class NetworkBandwidth(
    @StringRes val displayNameRes: Int,
    val scanResultValue: Int,
    val bandwidthMhz: Int
) {
    BW_20MHZ(R.string.bandwidth_20mhz, ScanResult.CHANNEL_WIDTH_20MHZ, 20),
    BW_40MHZ(R.string.bandwidth_40mhz, ScanResult.CHANNEL_WIDTH_40MHZ, 40),
    BW_80MHZ(R.string.bandwidth_80mhz, ScanResult.CHANNEL_WIDTH_80MHZ, 80),
    BW_160MHZ(R.string.bandwidth_160mhz, ScanResult.CHANNEL_WIDTH_160MHZ, 160),
    BW_80MHZ_PLUS(R.string.bandwidth_80mhz_plus, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ, 80);

    companion object {
        fun fromScanResult(channelWidth: Int): NetworkBandwidth =
            values().firstOrNull { it.scanResultValue == channelWidth } ?: BW_20MHZ
    }
}