package com.lsd.wififrankenstein.util

import com.topjohnwu.superuser.Shell





object WpsSocketUtils {

    private const val TAG = "WpsSocketUtils"





    private val CANDIDATE_DIRS = listOf(
        "/data/misc/wifi/wpa_supplicant",
        "/data/system/wpa_supplicant",
        "/var/run/wpa_supplicant",
        "/data/vendor/wifi/wpa",
        "/data/misc/wifi/sockets"
    )





    fun findControlSocketDir(interfaceName: String = "wlan0"): String? {
        return CANDIDATE_DIRS.find { path ->
            try {
                val result = Shell.cmd("test -S $path/$interfaceName && echo 'EXISTS'").exec()
                result.out.contains("EXISTS")
            } catch (e: Exception) {
                Log.w(TAG, "Socket check failed for $path/$interfaceName", e)
                false
            }
        }
    }





    fun ctrlDirForWpaCli(interfaceName: String = "wlan0"): String {
        return findControlSocketDir(interfaceName) ?: "/data/misc/wifi/sockets"
    }
}
