package com.lsd.wififrankenstein.util

import android.annotation.SuppressLint
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi

class WiFiManagerWrapper(
    private val wifiManager: WifiManager
) {
    fun wiFiEnabled(): Boolean = runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)

    fun startScan(): Boolean = runCatching { wifiManager.startScan() }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun scanResults(): List<ScanResult> = runCatching {
        wifiManager.scanResults ?: listOf()
    }.getOrDefault(listOf())

    fun wiFiInfo(): WifiInfo? = runCatching { wifiManager.connectionInfo }.getOrNull()

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
}