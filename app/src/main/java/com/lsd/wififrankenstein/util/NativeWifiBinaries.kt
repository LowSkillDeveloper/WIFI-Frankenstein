package com.lsd.wififrankenstein.util

import android.content.Context
import android.os.Build
import com.topjohnwu.superuser.Shell
import java.io.File

object NativeWifiBinaries {

    const val WPA_CLI = "wpa_cli"
    const val WPA_CLI_32 = "wpa_cli-32"
    const val WPA_SUPPLICANT = "wpa_supplicant"
    const val WPA_SUPPLICANT_32 = "wpa_supplicant-32"
    const val IW = "iw"
    const val IW_32 = "iw-32"
    const val PIXIEDUST = "pixiedust"
    const val PIXIEDUST_32 = "pixiedust-32"
    const val WPA_SUPPLICANT_CONF = "wpa_supplicant.conf"

    const val CTRL_DIR_VENDOR = "/data/vendor/wifi/wpa/wififrankenstein"
    const val CTRL_DIR_LEGACY = "/data/misc/wifi/wififrankenstein"

    private const val TAG = "NativeWifiBinaries"
    private val lock = Any()

    fun binaryDir(context: Context): String = context.filesDir.absolutePath

    fun isArmArchitecture(context: Context): Boolean =
        ChrootManager.get(context).isArmArchitecture()

    fun archSuffix(): String {
        val has64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        return if (has64 && Build.VERSION.SDK_INT >= 24) "" else "-32"
    }

    fun wpaSupplicantAssetName(): String =
        if (archSuffix() == "") WPA_SUPPLICANT else WPA_SUPPLICANT_32

    fun iwAssetName(): String =
        if (archSuffix() == "") IW else IW_32

    fun pixiedustAssetName(): String =
        if (archSuffix() == "") PIXIEDUST else PIXIEDUST_32

    fun ctrlDir(): String =
        if (Build.VERSION.SDK_INT >= 28) CTRL_DIR_VENDOR else CTRL_DIR_LEGACY

    fun ctrlSocketPath(): String = "${ctrlDir()}/wlan0"

    private val binaryAssets: List<String>
        get() = listOf(
            WPA_CLI,
            WPA_CLI_32,
            WPA_SUPPLICANT,
            WPA_SUPPLICANT_32,
            IW,
            IW_32,
            PIXIEDUST,
            PIXIEDUST_32,
            WPA_SUPPLICANT_CONF
        )

    fun libraryAssets(): List<String> {
        return if (archSuffix().isEmpty()) {
            listOf(
                "libnl-3.so",
                "libnl-genl-3.so",
                "libnl-route-3.so"
            )
        } else {
            listOf(
                "libnl-3.so-32",
                "libnl-genl-3.so-32",
                "libnl-route-3.so-32"
            )
        }
    }

    fun ensure(context: Context): Boolean {
        synchronized(lock) {
            var ok = true
            for (asset in binaryAssets + libraryAssets()) {
                val target = File(binaryDir(context), asset)




                if (!copyAsset(context, asset, target)) {
                    Log.e(TAG, "Failed to copy asset: $asset")
                    ok = false
                }
            }
            if (ok) {
                for (asset in binaryAssets) {
                    if (asset.endsWith(".conf")) continue
                    Shell.cmd("chmod 755 ${binaryDir(context)}/$asset").exec()
                }
                for (asset in libraryAssets()) {
                    Shell.cmd("chmod 755 ${binaryDir(context)}/$asset").exec()
                }
                if (archSuffix().isNotEmpty()) {
                    createLibrarySymlinks(context)
                }
            }
            return ok
        }
    }

    fun allBinariesPresent(context: Context): Boolean =
        (binaryAssets + libraryAssets()).all { asset ->
            val f = File(binaryDir(context), asset)
            f.exists() && f.length() > 0
        }

    private fun createLibrarySymlinks(context: Context) {
        val dir = binaryDir(context)
        val symlinkConfigs = listOf(
            Pair("libnl-3.so-32", "libnl-3.so"),
            Pair("libnl-genl-3.so-32", "libnl-genl-3.so"),
            Pair("libnl-route-3.so-32", "libnl-route-3.so")
        )
        symlinkConfigs.forEach { (sourceFile, linkName) ->
            Shell.cmd("rm -f $dir/$linkName 2>/dev/null").exec()
            Shell.cmd("cd $dir && ln -sf $sourceFile $linkName 2>/dev/null").exec()
        }
    }

    private fun copyAsset(context: Context, asset: String, target: File): Boolean {
        return try {
            context.assets.open(asset).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy $asset", e)
            false
        }
    }
}
