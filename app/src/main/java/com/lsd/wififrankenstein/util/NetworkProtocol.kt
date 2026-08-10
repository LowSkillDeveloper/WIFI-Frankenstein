package com.lsd.wififrankenstein.util

import android.net.wifi.ScanResult
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.lsd.wififrankenstein.R

enum class NetworkProtocol(
    val protocolId: Int,
    @StringRes val fullNameRes: Int,
    @StringRes val shortNameRes: Int
) {
    UNKNOWN(0, R.string.protocol_unknown, R.string.protocol_unknown_short),
    LEGACY(1, R.string.protocol_legacy, R.string.protocol_legacy_short),
    N_PROTOCOL(4, R.string.protocol_n, R.string.protocol_n_short),
    AC_PROTOCOL(5, R.string.protocol_ac, R.string.protocol_ac_short),
    AX_PROTOCOL(6, R.string.protocol_ax, R.string.protocol_ax_short),
    AD_PROTOCOL(7, R.string.protocol_ad, R.string.protocol_ad_short),
    BE_PROTOCOL(8, R.string.protocol_be, R.string.protocol_be_short);

    companion object {
        fun fromScanResult(scanResult: ScanResult): NetworkProtocol =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fromProtocolId(scanResult.wifiStandard)
            } else {
                UNKNOWN
            }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun fromProtocolId(standardId: Int): NetworkProtocol =
            values().firstOrNull { it.protocolId == standardId } ?: UNKNOWN
    }
}