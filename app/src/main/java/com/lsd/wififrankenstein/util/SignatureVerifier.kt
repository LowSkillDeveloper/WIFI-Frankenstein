package com.lsd.wififrankenstein.util

import android.content.Context
import android.content.pm.PackageManager
import com.lsd.wififrankenstein.R
import java.security.MessageDigest

object SignatureVerifier {

    fun getAppIdentifier(context: Context): String {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "unknown"
        }

        val isOfficial = isOfficialBuild(context)
        val isDebug = context.getString(R.string.is_debug_build).toBoolean()

        val status = if (isOfficial) "official" else "unofficial"
        val debugSuffix = if (isDebug) "-debug" else ""

        return "WIFIFrankenstein-$versionName-$status$debugSuffix"
    }

    fun isOfficialBuild(context: Context): Boolean {
        return try {
            val expectedSignature = context.getString(R.string.official_signature_sha256)
            val currentSignature = getCurrentSignatureSha256(context)

            val normalizedExpected = expectedSignature.replace(":", "").lowercase()
            val normalizedCurrent = currentSignature?.lowercase()

            normalizedCurrent == normalizedExpected
        } catch (e: Exception) {
            false
        }
    }

    private fun getCurrentSignatureSha256(context: Context): String? {
        return try {
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures
            }

            val signature = signatures?.get(0) ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(signature.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}