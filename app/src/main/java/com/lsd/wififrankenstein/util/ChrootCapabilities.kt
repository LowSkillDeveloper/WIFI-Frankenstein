package com.lsd.wififrankenstein.util

import android.content.Context

object ChrootCapabilities {
    fun isAvailable(context: Context): Boolean {
        return try {
            val type = ChrootManager.get(context).getChrootType()
            type is com.lsd.wififrankenstein.util.ChrootType.Root ||
                    type is com.lsd.wififrankenstein.util.ChrootType.RootWithoutChroot ||
                    type is com.lsd.wififrankenstein.util.ChrootType.Rootless
        } catch (_: Exception) {
            false
        }
    }

    fun isRootAvailable(context: Context): Boolean {
        return try {
            val type = ChrootManager.get(context).getChrootType()
            type is com.lsd.wififrankenstein.util.ChrootType.Root ||
                    type is com.lsd.wififrankenstein.util.ChrootType.RootMissing ||
                    type is com.lsd.wififrankenstein.util.ChrootType.RootWithoutChroot
        } catch (_: Exception) {
            false
        }
    }

    fun hasChrootTools(context: Context): Boolean {
        return try {
            val type = ChrootManager.get(context).getChrootType()
            type is com.lsd.wififrankenstein.util.ChrootType.Root
        } catch (_: Exception) {
            false
        }
    }
}
