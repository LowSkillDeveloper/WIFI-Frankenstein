package com.lsd.wififrankenstein.ui.databasefinder

data class SearchResult(
    val ssid: String,
    val bssid: String,
    val password: String?,
    val wpsPin: String?,
    val source: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val rawBssid: Long? = null
) {
    companion object {
        private val HEX_PAIR_REGEX = Regex("(.{2})")
    }

    fun getFormattedBssid(): String {
        return if (rawBssid != null && bssid.all { it.isDigit() }) {
            formatMacAddress(rawBssid)
        } else {
            bssid
        }
    }

    private fun formatMacAddress(decimal: Long): String {
        return String.format("%012X", decimal)
            .replace(HEX_PAIR_REGEX, "$1:").dropLast(1)
    }

    override fun toString(): String {
        return "SearchResult(ssid='$ssid', bssid='$bssid', password=$password, wpsPin=$wpsPin, source='$source', lat=$latitude, lon=$longitude, rawBssid=$rawBssid)"
    }
}

data class PageSlice(
    val items: List<SearchResult>,
    val hasMore: Boolean
)