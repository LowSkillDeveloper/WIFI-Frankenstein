package com.lsd.wififrankenstein.util

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.qrgenerator.QrCodeGeneratorFragment
import com.lsd.wififrankenstein.utils.SecurityType

object QrNavigationHelper {

    fun navigateToQrGenerator(
        fragment: Fragment,
        ssid: String,
        password: String = "",
        security: String = "WPA"
    ) {
        try {
            val bundle = Bundle().apply {
                putString("ssid", ssid)
                putString("password", password)
                putString("security", security)
            }
            fragment.findNavController().navigate(R.id.nav_qr_generator, bundle)
        } catch (e: Exception) {

        }
    }

    fun createQrGeneratorFragment(
        ssid: String,
        password: String = "",
        security: String = "WPA"
    ): QrCodeGeneratorFragment {
        return QrCodeGeneratorFragment.newInstance(ssid, password, security)
    }

    fun determineSecurityType(capabilities: String): String {
        return when {
            capabilities.contains("WPA3", ignoreCase = true) -> SecurityType.WPA3.value
            capabilities.contains("WPA", ignoreCase = true) -> SecurityType.WPA.value
            capabilities.contains("WEP", ignoreCase = true) -> SecurityType.WEP.value
            capabilities.contains("NONE", ignoreCase = true) ||
                    capabilities.contains("OPEN", ignoreCase = true) -> SecurityType.NONE.value
            else -> SecurityType.WPA.value
        }
    }

    fun hasValidCredentials(password: String?, wpsPin: String?): Boolean {
        return !password.isNullOrBlank() || !wpsPin.isNullOrBlank()
    }
}