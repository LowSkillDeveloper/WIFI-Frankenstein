package com.lsd.wififrankenstein.utils

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.lsd.wififrankenstein.R

object NavigationHelper {

    fun Fragment.navigateToQrGenerator(
        ssid: String,
        password: String = "",
        security: String = "WPA"
    ) {
        val bundle = Bundle().apply {
            putString("ssid", ssid)
            putString("password", password)
            putString("security", security)
        }

        try {
            findNavController().navigate(R.id.nav_qr_generator, bundle)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun Fragment.navigateToQrGeneratorWithWiFiData(
        ssid: String,
        password: String = "",
        securityType: SecurityType = SecurityType.WPA,
        hidden: Boolean = false
    ) {
        val bundle = Bundle().apply {
            putString("ssid", ssid)
            putString("password", password)
            putString("security", securityType.value)
            putBoolean("hidden", hidden)
        }

        try {
            findNavController().navigate(R.id.nav_qr_generator, bundle)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}