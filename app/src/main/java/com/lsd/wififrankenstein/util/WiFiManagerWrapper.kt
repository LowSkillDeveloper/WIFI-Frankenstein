package com.lsd.wififrankenstein.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

class WiFiManagerWrapper(
    private val wifiManager: WifiManager,
    private val context: Context
) {
    fun wiFiEnabled(): Boolean = runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)

    fun startScan(): Boolean = runCatching { wifiManager.startScan() }.getOrDefault(false)

    fun scanResults(): List<ScanResult> = runCatching {
        if (!hasWifiPermission()) return@runCatching emptyList()
        wifiManager.scanResults ?: listOf()
    }.getOrDefault(emptyList())

    fun wiFiInfo(): WifiInfo? = runCatching {
        if (!hasWifiPermission()) return@runCatching null
        @Suppress("DEPRECATION")
        wifiManager.connectionInfo
    }.getOrNull()

    fun is5GHzBandSupported(): Boolean = wifiManager.is5GHzBandSupported

    fun is6GHzBandSupported(): Boolean =
        if (minVersionR()) {
            wifiManager.is6GHzBandSupported
        } else {
            false
        }

    fun isScanThrottleEnabled(): Boolean =
        if (minVersionR()) {
            isScanThrottleEnabledR()
        } else {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isScanThrottleEnabledR(): Boolean = wifiManager.isScanThrottleEnabled

    private fun minVersionR(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private fun hasWifiPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
