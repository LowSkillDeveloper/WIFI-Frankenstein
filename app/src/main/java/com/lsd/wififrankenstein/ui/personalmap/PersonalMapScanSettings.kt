package com.lsd.wififrankenstein.ui.personalmap

import android.content.SharedPreferences

object PersonalMapScanSettings {

    const val PREF_ADAPTIVE = "personal_map_scan_adaptive"

    const val PREF_INTERVAL_MANUAL = "personal_map_scan_interval"

    const val PREF_INTERVAL_STILL = "personal_map_scan_interval_still"
    const val PREF_INTERVAL_WALK = "personal_map_scan_interval_walk"
    const val PREF_INTERVAL_DRIVE = "personal_map_scan_interval_drive"
    const val PREF_INTERVAL_VERY_FAST = "personal_map_scan_interval_very_fast"

    const val PREF_KEEP_SCREEN_ON = "personal_map_keep_screen_on"

    const val DEFAULT_STILL_MS = 5_000L
    const val DEFAULT_WALK_MS = 3_000L
    const val DEFAULT_DRIVE_MS = 2_000L
    const val DEFAULT_VERY_FAST_MS = 1_000L

    const val MIN_MS = 1_000L

    const val SPEED_STILL_MPS = 0.1f

    const val SPEED_WALK_MPS = 2.2352f

    const val SPEED_DRIVE_MPS = 13.4f

    const val LOCATION_UPDATE_MIN_MS = 2_000L

    fun isAdaptive(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_ADAPTIVE, true)

    fun isKeepScreenOn(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_KEEP_SCREEN_ON, false)

    fun intervalFor(prefs: SharedPreferences, speedMps: Float?): Long {
        if (!isAdaptive(prefs)) {
            return prefs.getLong(PREF_INTERVAL_MANUAL, DEFAULT_WALK_MS).coerceAtLeast(MIN_MS)
        }
        val resolved = when {
            speedMps == null || speedMps < SPEED_STILL_MPS ->
                prefs.getLong(PREF_INTERVAL_STILL, DEFAULT_STILL_MS)

            speedMps < SPEED_WALK_MPS ->
                prefs.getLong(PREF_INTERVAL_WALK, DEFAULT_WALK_MS)

            speedMps < SPEED_DRIVE_MPS ->
                prefs.getLong(PREF_INTERVAL_DRIVE, DEFAULT_DRIVE_MS)

            else ->
                prefs.getLong(PREF_INTERVAL_VERY_FAST, DEFAULT_VERY_FAST_MS)
        }
        return resolved.coerceAtLeast(MIN_MS)
    }
}
