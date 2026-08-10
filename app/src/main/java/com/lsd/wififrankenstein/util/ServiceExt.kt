package com.lsd.wififrankenstein.util

import android.app.Service
import android.os.Build






fun Service.stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    } else {
        stopForeground(true)
    }
}
