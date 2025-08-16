package com.lsd.wififrankenstein.ui.databasefinder

data class AdvancedSearchQuery(
    val bssid: String = "",
    val essid: String = "",
    val password: String = "",
    val wpsPin: String = "",
    val caseSensitive: Boolean = false
) {
    fun hasContent(): Boolean {
        return bssid.isNotBlank() || essid.isNotBlank() ||
                password.isNotBlank() || wpsPin.isNotBlank()
    }

    fun containsWildcards(text: String): Boolean {
        return text.contains("*") || text.contains("□") || text.contains("◯")
    }

    fun convertWildcards(text: String): String {
        return text.replace("□", "_").replace("◯", "%")
    }

    fun filterWildcards(text: String): String {
        return text.replace("□", "").replace("◯", "")
    }
}