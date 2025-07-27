package com.lsd.wififrankenstein.shell

import android.content.Context
import com.topjohnwu.superuser.Shell

class ShellInitializer : Shell.Initializer() {
    override fun onInit(context: Context, shell: Shell): Boolean {
        return try {
            shell.newJob()
                .add("export PATH=\$PATH:/system/bin:/system/xbin:/vendor/bin")
                .add("umask 022")
                .add("export ANDROID_DATA=/data")
                .add("export ANDROID_ROOT=/system")
                .exec()
                .isSuccess
        } catch (e: Exception) {
            false
        }
    }
}