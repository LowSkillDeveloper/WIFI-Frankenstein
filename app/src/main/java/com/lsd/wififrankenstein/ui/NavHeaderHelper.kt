package com.lsd.wififrankenstein.ui

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.SignatureVerifier

object NavHeaderHelper {

    fun setupNavHeader(context: Context, headerView: View) {
        val titleTextView = headerView.findViewById<TextView>(R.id.nav_header_title)
        val subtitleTextView = headerView.findViewById<TextView>(R.id.nav_header_subtitle)

        setupTitleWithVersion(context, titleTextView)
        setupOfficialStatus(context, subtitleTextView)
    }

    private fun setupTitleWithVersion(context: Context, titleTextView: TextView) {
        try {
            @Suppress("DEPRECATION")
            val versionName =
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            val appName = context.getString(R.string.nav_header_title)
            titleTextView.text =
                context.getString(R.string.nav_header_title_with_version, appName, versionName)
        } catch (e: PackageManager.NameNotFoundException) {
            titleTextView.text = context.getString(R.string.nav_header_title)
        }
    }

    private fun setupOfficialStatus(context: Context, subtitleTextView: TextView) {
        if (!SignatureVerifier.isOfficialBuild(context)) {
            subtitleTextView.text = getWarningText(context)
            subtitleTextView.setTextColor(ContextCompat.getColor(context, R.color.error_red))
            subtitleTextView.setOnClickListener {
                showWarningDialog(context)
            }
        }
    }

    private fun getWarningText(context: Context): String {
        return context.getString(R.string.drw_warning_unofficial)
    }

    private fun showWarningDialog(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.drw_warning_title))
            .setMessage(context.getString(R.string.drw_warning_message))
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
}