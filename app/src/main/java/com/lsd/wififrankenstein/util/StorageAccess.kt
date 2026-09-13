package com.lsd.wififrankenstein.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

object StorageAccess {
    fun canUseFileApi(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isRootAvailable(context: Context): Boolean {
        return try {
            ChrootCapabilities.isRootAvailable(context)
        } catch (_: Exception) {
            false
        }
    }

    fun hasChrootTools(context: Context): Boolean {
        return try {
            ChrootCapabilities.hasChrootTools(context)
        } catch (_: Exception) {
            false
        }
    }

    fun canUsePrivileged(context: Context): Boolean {
        return isRootAvailable(context) || hasChrootTools(context)
    }

    fun canAccessExternal(context: Context): Boolean {
        return canUseFileApi(context) || canUsePrivileged(context)
    }

    fun canWriteStorage(context: Context): Boolean {
        return try {
            isRootAvailable(context) ||
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                    Environment.isExternalStorageManager()
        } catch (_: Exception) {
            false
        }
    }
}
