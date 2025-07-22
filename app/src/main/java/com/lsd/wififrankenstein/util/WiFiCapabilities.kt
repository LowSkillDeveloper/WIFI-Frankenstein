package com.lsd.wififrankenstein.util

import android.net.wifi.ScanResult
import android.os.Build
import androidx.annotation.StringRes
import com.lsd.wififrankenstein.R

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
                    supportsRtt = try {
                        scanResult.is80211mcResponder
                    } catch (e: Exception) {
                        false
                    },
                    supportsNtb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            scanResult.is80211azNtbResponder
                        } catch (e: Exception) {
                            false
                        }
                    } else false,
                    supportsTwt = try {
                        scanResult.isTwtResponder
                    } catch (e: Exception) {
                        false
                    },
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

enum class WiFiAdvancedFeature(@StringRes val textResource: Int) {
    RTT(R.string.wifi_rtt_responder),
    NTB(R.string.wifi_ntb_responder),
    TWT(R.string.wifi_twt_responder),
    UNTRUSTED(R.string.wifi_untrusted),
    MLD(R.string.wifi_mld_support)
}