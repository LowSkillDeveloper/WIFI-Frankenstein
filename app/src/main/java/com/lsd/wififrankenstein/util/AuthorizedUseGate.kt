package com.lsd.wififrankenstein.util

import android.content.Context
import android.view.LayoutInflater
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.DialogAuthorizedUseBinding

object AuthorizedUseGate {

    private const val PREFS_NAME = "settings"
    private const val DISMISSED_PREFIX = "authgate_dismissed_"

    fun confirm(
        fragment: Fragment,
        featureKey: String,
        featureName: String,
        extraWarningRes: Int? = null,
        onConfirmed: () -> Unit
    ) {
        val context = fragment.context ?: run {
            onConfirmed()
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dismissedKey = DISMISSED_PREFIX + featureKey
        if (prefs.getBoolean(dismissedKey, false)) {
            onConfirmed()
            return
        }

        var dontAskAgain = false
        val binding = DialogAuthorizedUseBinding.inflate(LayoutInflater.from(context))
        binding.checkBoxDontAsk.setOnCheckedChangeListener { _, checked ->
            dontAskAgain = checked
        }

        val message = if (extraWarningRes != null) {
            context.getString(R.string.authgate_message, featureName) + "\n\n" +
                context.getString(extraWarningRes)
        } else {
            context.getString(R.string.authgate_message, featureName)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.authgate_title)
            .setMessage(message)
            .setView(binding.root)
            .setCancelable(false)
            .setPositiveButton(R.string.authgate_confirm) { _, _ ->
                if (dontAskAgain) {
                    prefs.edit { putBoolean(dismissedKey, true) }
                }
                onConfirmed()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun resetAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            prefs.all.keys
                .filter { it.startsWith(DISMISSED_PREFIX) }
                .forEach { remove(it) }
        }
    }
}
