package com.lsd.wififrankenstein.util

import android.net.wifi.ScanResult
import android.os.Build

data class WiFiAdvancedCapabilities(
    val supportsRtt: Boolean = false,
    val supportsNtb: Boolean = false,
    val supportsTwt: Boolean = false,
    val isUntrusted: Boolean = false,
    val supportsMld: Boolean = false
) {
    companion object {
        fun fromScanResult(scanResult: ScanResult): WiFiAdvancedCapabilities {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WiFiAdvancedCapabilities(
                    supportsRtt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            scanResult.is80211mcResponder
                        } catch (e: Exception) {
                            false
                        }
                    } else false,
                    supportsNtb = if (Build.VERSION.SDK_INT >= 35) {
                        try {
                            scanResult.is80211azNtbResponder
                        } catch (e: Exception) {
                            false
                        }
                    } else false,
                    supportsTwt = if (Build.VERSION.SDK_INT >= 35) {
                        try {
                            scanResult.isTwtResponder
                        } catch (e: Exception) {
                            false
                        }
                    } else false,
                    isUntrusted = getUntrustedStatus(scanResult),
                    supportsMld = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try {
                            scanResult.apMldMacAddress != null
                        } catch (e: Exception) {
                            false
                        }
                    } else false
                )
            } else {
                WiFiAdvancedCapabilities(
                    isUntrusted = getUntrustedStatus(scanResult)
                )
            }
        }

        private fun getUntrustedStatus(scanResult: ScanResult): Boolean {
            return try {
                val untrustedField = ScanResult::class.java.getDeclaredField("untrusted")
                untrustedField.isAccessible = true
                untrustedField.getBoolean(scanResult)
            } catch (e: Exception) {
                false
            }
        }
    }
}