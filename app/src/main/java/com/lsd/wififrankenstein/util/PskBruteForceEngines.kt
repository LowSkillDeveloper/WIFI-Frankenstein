package com.lsd.wififrankenstein.util

import android.content.Context
import android.os.Build










object PskBruteForceEngines {

    const val NATIVE_REQUIRES_LEGACY_WIFI_API = 29

    fun isNativeSupported(context: Context): Boolean {
        val targetSdk = context.applicationInfo.targetSdkVersion
        return Build.VERSION.SDK_INT < NATIVE_REQUIRES_LEGACY_WIFI_API ||
                targetSdk < NATIVE_REQUIRES_LEGACY_WIFI_API
    }
}
