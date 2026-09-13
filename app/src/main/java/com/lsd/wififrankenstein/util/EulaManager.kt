package com.lsd.wififrankenstein.util

import android.content.Context

object EulaManager {

    const val CURRENT_EULA_VERSION = 2

    private const val PREFS_NAME = "settings"
    private const val KEY_ACCEPTED_VERSION = "eula_accepted_version"
    private const val KEY_ACCEPTED_AT = "eula_accepted_at"

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ACCEPTED_VERSION, 0) >= CURRENT_EULA_VERSION

    fun markAccepted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACCEPTED_VERSION, CURRENT_EULA_VERSION)
            .putLong(KEY_ACCEPTED_AT, System.currentTimeMillis())
            .apply()
    }
}
