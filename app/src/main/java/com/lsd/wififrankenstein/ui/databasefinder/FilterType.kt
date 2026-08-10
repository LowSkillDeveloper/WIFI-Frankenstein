package com.lsd.wififrankenstein.ui.databasefinder

import androidx.annotation.StringRes
import com.lsd.wififrankenstein.R

enum class FilterType(
    val key: String,
    @StringRes val labelRes: Int
) {
    BSSID("bssid", R.string.filter_bssid),
    ESSID("essid", R.string.filter_essid),
    PASSWORD("wifi_password", R.string.filter_wifi_password),
    WPS_PIN("wps_pin", R.string.filter_wps_pin);

    companion object {
        fun fromKey(key: String): FilterType? = entries.find { it.key == key }
    }
}
