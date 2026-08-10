package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import com.lsd.wififrankenstein.R

class UserManager(private val context: Context) {

    fun getTextGroup(level: Int): String {
        return when (level) {
            -2 -> context.getString(R.string.access_level_banned)
            -1 -> context.getString(R.string.access_level_no_logged)
            0 -> context.getString(R.string.access_level_guest)
            1 -> context.getString(R.string.access_level_user)
            2 -> context.getString(R.string.access_level_developer)
            3 -> context.getString(R.string.access_level_admin)
            else -> ""
        }
    }

    fun getErrorDesc(error: String): String {
        return when (error) {
            "database" -> context.getString(R.string.error_database_maintenance)
            "loginfail" -> context.getString(R.string.error_incorrect_credentials)
            "form" -> context.getString(R.string.error_form_fields)
            "cooldown" -> context.getString(R.string.error_cooldown)
            else -> context.getString(R.string.unknown_error).format(error)
        }
    }
}