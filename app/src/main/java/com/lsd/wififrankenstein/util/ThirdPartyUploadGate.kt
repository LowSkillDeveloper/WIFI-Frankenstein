package com.lsd.wififrankenstein.util

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R

object ThirdPartyUploadGate {

    fun confirm(context: Context, serviceName: String, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.thirdparty_upload_title)
            .setMessage(context.getString(R.string.thirdparty_upload_message, serviceName))
            .setCancelable(false)
            .setPositiveButton(R.string.thirdparty_upload_continue) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
