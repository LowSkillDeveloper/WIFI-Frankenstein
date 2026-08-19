package com.lsd.wififrankenstein.util

import android.content.Context

object ChrootCapabilities {
    fun isAvailable(context: Context): Boolean {
        return try {
            val type = ChrootManager.get(context).getChrootType()
            type is ChrootType.Root ||
                    type is ChrootType.RootWithoutChroot ||
                    type is ChrootType.Rootless
        } catch (_: Exception) {
            false
        }
    }

    fun isRootAvailable(context: Context): Boolean {
        return try {
            val type = ChrootManager.get(context).getChrootType()
            type is ChrootType.Root ||
                    type is ChrootType.RootMissing ||
                    type is ChrootType.RootWithoutChroot
        } catch (_: Exception) {
            false
        }
    }

    fun hasChrootTools(context: Context): Boolean {
        return try {
            val type = ChrootManager.get(context).getChrootType()
            type is ChrootType.Root
        } catch (_: Exception) {
            false
        }
    }
}
