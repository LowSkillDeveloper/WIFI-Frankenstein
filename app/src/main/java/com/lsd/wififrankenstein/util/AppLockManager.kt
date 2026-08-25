package com.lsd.wififrankenstein.util

import android.content.Context
import androidx.biometric.BiometricManager

object AppLockManager {

    private const val PREFS_NAME = "settings"
    private const val KEY_ENABLED = "app_lock_enabled"

    @Volatile
    var unlocked = false
        private set

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            unlocked = false
        }
    }

    fun isAvailable(context: Context): Boolean =
        canAuthenticate(context) == BiometricManager.BIOMETRIC_SUCCESS

    fun hasBiometricsEnrolled(context: Context): Boolean =
        try {
            BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                    BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Exception) {
            false
        }

    private fun canAuthenticate(context: Context): Int =
        try {
            BiometricManager.from(context)
                .canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
        } catch (_: Exception) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        }

    fun shouldLock(context: Context): Boolean =
        isEnabled(context) && !unlocked

    internal fun markUnlocked() {
        unlocked = true
    }

    fun resetSession() {
        unlocked = false
    }
}
