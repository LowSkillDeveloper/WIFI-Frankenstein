package com.lsd.wififrankenstein.util

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

object SymlinkCompat {

    @Throws(IOException::class)
    fun createSymbolicLink(link: File, target: String) {
        link.parentFile?.mkdirs()
        try {
            Os.symlink(target, link.absolutePath)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EEXIST) {
                throw IOException(
                    "createSymbolicLink($target -> ${link.absolutePath}) failed, errno=${e.errno}",
                    e
                )
            }
        }
    }
}
