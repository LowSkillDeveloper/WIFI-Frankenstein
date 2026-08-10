package com.lsd.wififrankenstein.util

import android.content.Context
import com.lsd.wififrankenstein.R

object DbFieldFormatter {

    private val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
    private val DATE_FORMATTER = ThreadLocal.withInitial {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    }

    fun longToIp(ipLong: Long?): String? {
        if (ipLong == null || ipLong == 0L) return null
        val positiveIp = if (ipLong < 0) ipLong + 4294967296L else ipLong
        return "${(positiveIp shr 24) and 0xFF}.${(positiveIp shr 16) and 0xFF}.${(positiveIp shr 8) and 0xFF}.${positiveIp and 0xFF}"
    }

    fun longToIpWithCidr(ipLong: Long?, iprange: Int?): String? {
        val ip = longToIp(ipLong) ?: return null
        return if (iprange == 1) "$ip/24" else ip
    }

    fun longToMac(decimal: Long): String {
        val hex = String.format("%012X", decimal)
        return hex.chunked(2).joinToString(":").dropLast(1)
    }

    fun formatTime(value: Any?): String? {
        if (value == null) return null
        return when (value) {
            is String -> {
                if (value.matches(DATE_REGEX)) {
                    value
                } else null
            }

            is Long -> {
                DATE_FORMATTER.get().format(java.util.Date(value))
            }

            is Int -> {
                DATE_FORMATTER.get().format(java.util.Date(value.toLong()))
            }

            else -> value.toString()
        }
    }

    fun noWifiKeyLabel(context: Context, value: Int?): String? {
        if (value == null) return null
        return when (value) {
            0 -> context.getString(R.string.no_wifi_key_has_key)
            1 -> context.getString(R.string.no_wifi_key_none)
            2 -> context.getString(R.string.no_wifi_key_empty)
            3 -> context.getString(R.string.no_wifi_key_not_accessible)
            4 -> context.getString(R.string.no_wifi_key_not_implemented)
            5 -> context.getString(R.string.no_wifi_key_too_long)
            else -> value.toString()
        }
    }

    fun noBssidLabel(context: Context, value: Int?): String? {
        if (value == null) return null
        return when (value) {
            0 -> context.getString(R.string.no_bssid_has)
            1 -> context.getString(R.string.no_bssid_none)
            else -> value.toString()
        }
    }

    fun noWpsLabel(context: Context, value: Int?): String? {
        if (value == null) return null
        return when (value) {
            0 -> context.getString(R.string.no_wps_has)
            1 -> context.getString(R.string.no_wps_none)
            else -> value.toString()
        }
    }

    fun sourceLabel(context: Context, value: Int?): String? {
        if (value == null) return null
        return when (value) {
            0 -> context.getString(R.string.source_3wifi)
            1 -> context.getString(R.string.source_3wifi_dead)
            2 -> context.getString(R.string.source_google)
            3 -> context.getString(R.string.source_yandex)
            4, 5 -> context.getString(R.string.source_apple)
            6 -> context.getString(R.string.source_microsoft)
            8 -> context.getString(R.string.source_skyhook)
            else -> context.getString(R.string.source_unknown, value)
        }
    }

    fun iprangeLabel(context: Context, value: Int?): String? {
        if (value == null) return null
        return when (value) {
            0 -> context.getString(R.string.iprange_standard)
            1 -> context.getString(R.string.iprange_range)
            else -> value.toString()
        }
    }

    fun hiddenLabel(value: Any?): String? {
        if (value == null) return null
        val intValue = when (value) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        } ?: return null
        return if (intValue == 1) "Yes" else "No"
    }

    fun radioOffLabel(value: Any?): String? {
        if (value == null) return null
        val intValue = when (value) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        } ?: return null
        return if (intValue == 1) "Yes" else "No"
    }

    fun authorizationLabel(value: String?): String? {
        if (value == null || value.isEmpty()) return null
        return if (value.contains(':')) {
            val parts = value.split(':', limit = 2)
            "Login: ${parts[0]}, Password: ${parts[1]}"
        } else value
    }

    fun fieldNameDescription(context: Context, fieldName: String): String {
        val resources = context.resources
        return when (fieldName.lowercase()) {
            "id" -> resources.getString(R.string.field_id)
            "time" -> resources.getString(R.string.field_time)
            "cmtid" -> resources.getString(R.string.field_cmtid)
            "iprange" -> resources.getString(R.string.field_iprange)
            "ip" -> resources.getString(R.string.field_ip)
            "port" -> resources.getString(R.string.field_port)
            "authorization" -> resources.getString(R.string.field_authorization)
            "name" -> resources.getString(R.string.field_name)
            "radiooff" -> resources.getString(R.string.field_radiooff)
            "hidden" -> resources.getString(R.string.field_hidden)
            "nobssid" -> resources.getString(R.string.field_nobssid)
            "bssid" -> resources.getString(R.string.field_bssid)
            "essid" -> resources.getString(R.string.field_essid)
            "security" -> resources.getString(R.string.field_security)
            "nowifikey" -> resources.getString(R.string.field_nowifikey)
            "wifikey" -> resources.getString(R.string.field_wifikey)
            "nowps" -> resources.getString(R.string.field_nowps)
            "wpspin" -> resources.getString(R.string.field_wpspin)
            "lanip" -> resources.getString(R.string.field_lanip)
            "lanmask" -> resources.getString(R.string.field_lanmask)
            "wanip" -> resources.getString(R.string.field_wanip)
            "wanmask" -> resources.getString(R.string.field_wanmask)
            "wangateway" -> resources.getString(R.string.field_wangateway)
            "dns1" -> resources.getString(R.string.field_dns1)
            "dns2" -> resources.getString(R.string.field_dns2)
            "dns3" -> resources.getString(R.string.field_dns3)
            "latitude" -> resources.getString(R.string.field_latitude)
            "longitude" -> resources.getString(R.string.field_longitude)
            "source" -> resources.getString(R.string.field_source)
            "quadkey" -> resources.getString(R.string.field_quadkey)
            "comment" -> resources.getString(R.string.field_comment)
            else -> fieldName
        }
    }
}
