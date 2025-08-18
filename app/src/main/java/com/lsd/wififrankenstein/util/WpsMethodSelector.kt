package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.wifi.ScanResult
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.lsd.wififrankenstein.R

class WpsMethodSelector(
    private val context: Context,
    private val callbacks: WpsRootConnectHelper.WpsConnectCallbacks
) {

    fun showMethodSelection(network: ScanResult, databasePin: String? = null) {
        val methods = arrayOf(
            context.getString(R.string.wps_method_3),
            context.getString(R.string.wps_method_2),
            context.getString(R.string.wps_method_1)
        )

        AlertDialog.Builder(context)
            .setTitle(R.string.wps_method_selection_title)
            .setItems(methods) { _, which ->
                when (which) {
                    0 -> showWpsModeSelection(network, databasePin, ::useMethod3)
                    1 -> showWpsModeSelection(network, databasePin, ::useMethod2)
                    2 -> showWpsModeSelection(network, databasePin, ::useMethod1)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWpsModeSelection(
        network: ScanResult,
        databasePin: String?,
        methodExecutor: (ScanResult, String?) -> Unit
    ) {
        val options = mutableListOf<String>()
        options.add(context.getString(R.string.wps_mode_pbc))

        if (!databasePin.isNullOrBlank() && databasePin != "0") {
            options.add(context.getString(R.string.wps_use_database_pin, databasePin))
        }

        options.add(context.getString(R.string.wps_enter_custom_pin))

        AlertDialog.Builder(context)
            .setTitle(R.string.wps_mode_selection_title)
            .setItems(options.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        methodExecutor(network, null)
                    }
                    which == 1 && !databasePin.isNullOrBlank() && databasePin != "0" -> {
                        methodExecutor(network, databasePin)
                    }
                    else -> {
                        showPinInputDialog(network, methodExecutor)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPinInputDialog(
        network: ScanResult,
        methodExecutor: (ScanResult, String?) -> Unit
    ) {
        val editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(8))
            hint = context.getString(R.string.wps_pin_input_hint)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.wps_pin_input_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = editText.text.toString().trim()
                if (pin.length == 8 && pin.all { it.isDigit() }) {
                    methodExecutor(network, pin)
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.wps_pin_invalid),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun useMethod1(network: ScanResult, wpsPin: String?) {
        val helper = WpsRootConnectHelper(context, callbacks)
        helper.connectToNetworkWps(network, wpsPin)
    }

    private fun useMethod2(network: ScanResult, wpsPin: String?) {
        val helper = WpsRootConnectHelperMethod2(context, callbacks)
        helper.connectToNetworkWps(network, wpsPin)
    }

    private fun useMethod3(network: ScanResult, wpsPin: String?) {
        val helper = WpsRootConnectHelperMethod3(context, callbacks)
        helper.connectToNetworkWps(network, wpsPin)
    }
}