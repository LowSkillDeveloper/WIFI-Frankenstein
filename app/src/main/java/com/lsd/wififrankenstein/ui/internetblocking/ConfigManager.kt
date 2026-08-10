package com.lsd.wififrankenstein.ui.internetblocking

import android.content.Context
import android.content.SharedPreferences

class ConfigManager(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "internet_blocking_prefs"
        private const val KEY_SNI_LIST_TYPE = "sni_list_type"

        val DEFAULT_DOMAINS = listOf(
            "www.instagram.com",
            "www.facebook.com",
            "x.com",
            "www.youtube.com",
            "www.google.com"
        )
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSavedSniListType(): SniListType {
        val name = prefs.getString(KEY_SNI_LIST_TYPE, null) ?: return SniListType.BASE
        return try {
            SniListType.valueOf(name)
        } catch (_: Exception) {
            SniListType.BASE
        }
    }

    fun saveSniListType(type: SniListType) {
        prefs.edit().putString(KEY_SNI_LIST_TYPE, type.name).apply()
    }
}
