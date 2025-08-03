package com.lsd.wififrankenstein.ui.pixiedust

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import java.util.regex.Pattern

class ManualNetworkDialog(
    context: Context,
    private val onNetworkAdded: (ssid: String, bssid: String) -> Unit
) : Dialog(context) {

    private lateinit var ssidEdit: EditText
    private lateinit var bssidEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = layoutInflater.inflate(R.layout.dialog_manual_network, null)
        ssidEdit = view.findViewById(R.id.editTextSSID)
        bssidEdit = view.findViewById(R.id.editTextBSSID)

        bssidEdit.filters = arrayOf(
            InputFilter.AllCaps(),
            InputFilter.LengthFilter(17)
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pixiedust_manual_network_entry)
            .setView(view)
            .setPositiveButton(R.string.pixiedust_add_network) { _, _ ->
                val ssid = ssidEdit.text.toString().trim()
                val bssid = bssidEdit.text.toString().trim()

                if (ssid.isEmpty()) {
                    Toast.makeText(context, "SSID cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (!isValidBSSID(bssid)) {
                    Toast.makeText(context, R.string.pixiedust_invalid_bssid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                onNetworkAdded(ssid, bssid)
                dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> dismiss() }
            .create()
            .show()
    }

    private fun isValidBSSID(bssid: String): Boolean {
        val pattern = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
        return pattern.matcher(bssid).matches()
    }
}