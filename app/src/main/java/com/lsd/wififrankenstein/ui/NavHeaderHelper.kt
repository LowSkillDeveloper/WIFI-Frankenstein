package com.lsd.wififrankenstein.ui

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.View
import android.widget.TextView
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
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = packageInfo.versionName
            val appName = context.getString(R.string.nav_header_title)
            titleTextView.text = context.getString(R.string.nav_header_title_with_version, appName, versionName)
        } catch (e: PackageManager.NameNotFoundException) {
            titleTextView.text = context.getString(R.string.nav_header_title)
        }
    }

    private fun setupOfficialStatus(context: Context, subtitleTextView: TextView) {
        if (!SignatureVerifier.isOfficialBuild(context)) {
            subtitleTextView.text = getWarningText(context)
            subtitleTextView.setTextColor(Color.RED)
            subtitleTextView.setOnClickListener {
                showWarningDialog(context)
            }
        }
    }

    private fun getWarningText(context: Context): String {
        val isRussian = isRussianLocale(context)
        val encoded = if (isRussian) {
            "0J3QtdC+0YTQuNGG0LjQsNC70YzQvdCw0Y8g0LzQvtC00LjRhNC40YbQuNGA0L7QstCw0L3QvdCw0Y8g0LLQtdGA0YHQuNGP"
        } else {
            "VW5vZmZpY2lhbCBtb2RpZmllZCB2ZXJzaW9u"
        }

        return decodeBase64(encoded)
    }

    private fun showWarningDialog(context: Context) {
        val isRussian = isRussianLocale(context)

        val titleEncoded = if (isRussian) {
            "0J/RgNC10LTRg9C/0YDQtdC20LTQtdC90LjQtSDQviDQvNC+0LTQuNGE0LjQutCw0YbQuNC4INC/0YDQuNC70L7QttC10L3QuNGP"
        } else {
            "TW9kaWZpZWQgQXBwbGljYXRpb24gV2FybmluZw=="
        }

        val messageEncoded = if (isRussian) {
            "0K3RgtC+INC/0YDQuNC70L7QttC10L3QuNC1INCx0YvQu9C+INC80L7QtNC40YTQuNGG0LjRgNC+0LLQsNC90L4g0Lgg0LzQvtC20LXRgiDRgdC+0LTQtdGA0LbQsNGC0Ywg0L3QtdCw0LLRgtC+0YDQuNC30L7QstCw0L3QvdGL0LUg0LjQt9C80LXQvdC10L3QuNGPLiDQmNGB0L/QvtC70YzQt9GD0LnRgtC1INC90LAg0YHQstC+0Lkg0YHRgtGA0LDRhSDQuCDRgNC40YHQui4="
        } else {
            "VGhpcyBhcHBsaWNhdGlvbiBoYXMgYmVlbiBtb2RpZmllZCBhbmQgbWF5IGNvbnRhaW4gdW5hdXRob3JpemVkIGNoYW5nZXMuIFVzZSBhdCB5b3VyIG93biByaXNrLg=="
        }

        val title = decodeBase64(titleEncoded)
        val message = decodeBase64(messageEncoded)
        val okText = if (isRussian) "OK" else "OK"

        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(okText) { dialog, _ ->
                dialog.dismiss()
            }
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun decodeBase64(encoded: String): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            } else {
                val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                String(decoded, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "Modified version"
        }
    }

    private fun isRussianLocale(context: Context): Boolean {
        val locale = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        return locale.language == "ru"
    }
}