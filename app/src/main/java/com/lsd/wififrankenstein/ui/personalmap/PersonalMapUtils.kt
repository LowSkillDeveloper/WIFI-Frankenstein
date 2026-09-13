package com.lsd.wififrankenstein.ui.personalmap

import android.content.res.Resources
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalMapDbHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object PersonalMapUtils {

    private val dateTimeFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
    }
    private val timeShortFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    fun formatDateTime(value: Long, res: Resources): String =
        if (value > 0L) {
            dateTimeFormatter.get()!!.format(Date(value))
        } else {
            res.getString(R.string.not_available)
        }

    fun formatTimeShort(value: Long, res: Resources): String =
        if (value > 0L) {
            timeShortFormatter.get()!!.format(Date(value))
        } else {
            res.getString(R.string.not_available)
        }

    fun securityLabel(type: String?, res: Resources): String = when (type) {
        PersonalMapDbHelper.SECURITY_OPEN -> res.getString(R.string.pm_sec_open)
        PersonalMapDbHelper.SECURITY_WEP -> res.getString(R.string.pm_sec_wep)
        PersonalMapDbHelper.SECURITY_WPA -> res.getString(R.string.pm_sec_wpa)
        PersonalMapDbHelper.SECURITY_WPA2 -> res.getString(R.string.pm_sec_wpa2)
        PersonalMapDbHelper.SECURITY_WPA2_ENT -> res.getString(R.string.pm_sec_wpa2_ent)
        PersonalMapDbHelper.SECURITY_WPA3 -> res.getString(R.string.pm_sec_wpa3)
        PersonalMapDbHelper.SECURITY_WPA3_ENT -> res.getString(R.string.pm_sec_wpa3_ent)
        PersonalMapDbHelper.SECURITY_OWE -> res.getString(R.string.pm_sec_owe)
        else -> res.getString(R.string.pm_sec_unknown)
    }

    fun sourceLabel(source: String, res: Resources): String = when (source) {
        PersonalMapDbHelper.SOURCE_SCAN -> res.getString(R.string.pm_info_source_scan)
        PersonalMapDbHelper.SOURCE_IMPORT_WIGLE -> res.getString(R.string.pm_info_source_wigle)
        PersonalMapDbHelper.SOURCE_IMPORT_WIFILOC -> res.getString(R.string.pm_info_source_wifiloc)
        PersonalMapDbHelper.SOURCE_EXTERNAL -> res.getString(R.string.pm_info_source_external)
        PersonalMapDbHelper.SOURCE_RESTORE -> res.getString(R.string.pm_info_source_restore)
        else -> source
    }
}
