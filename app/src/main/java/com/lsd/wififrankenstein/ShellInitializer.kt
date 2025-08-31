package com.lsd.wififrankenstein

import android.content.Context
import com.topjohnwu.superuser.Shell

class ShellInitializer : Shell.Initializer() {
    override fun onInit(context: Context, shell: Shell): Boolean {
        return try {
            shell.newJob()
                .add("export PATH=\$PATH:/system/bin:/system/xbin")
                .add("umask 022")
                .exec()
            true
        } catch (e: Exception) {
            false
        }
    }
}