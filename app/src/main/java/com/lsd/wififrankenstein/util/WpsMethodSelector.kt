package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.wifi.ScanResult
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R

class WpsMethodSelector(
    private val context: Context,
    private val callbacks: WpsRootConnectHelper.WpsConnectCallbacks
) {

    companion object {
        const val NULL_PIN_IDENTIFIER = "##NULL_PIN##"
    }

    fun showMethodSelection(network: ScanResult, databasePin: String? = null) {



        val recommendedMethod = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) 2 else 0
        val methods = arrayOf(
            methodLabel(R.string.wps_method_3, recommendedMethod == 0),
            methodLabel(R.string.wps_method_2, recommendedMethod == 1),
            methodLabel(R.string.wps_method_1, recommendedMethod == 2)
        )

        MaterialAlertDialogBuilder(context)
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

    private fun methodLabel(resId: Int, isRecommended: Boolean): String {
        val base = context.getString(resId)
        return if (isRecommended) {
            context.getString(R.string.wps_method_recommended_format, base)
        } else {
            base
        }
    }

    private fun showWpsModeSelection(
        network: ScanResult,
        databasePin: String?,
        methodExecutor: (ScanResult, String?) -> Unit
    ) {
        val options = mutableListOf<String>()
        options.add(context.getString(R.string.wps_mode_pbc))
        options.add(context.getString(R.string.wps_use_empty_pin))
        options.add(context.getString(R.string.wps_use_null_pin))

        if (!databasePin.isNullOrBlank() && databasePin != "0") {
            options.add(context.getString(R.string.wps_use_database_pin, databasePin))
        }

        options.add(context.getString(R.string.wps_enter_custom_pin))

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.wps_mode_selection_title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        methodExecutor(network, null)
                    }

                    1 -> {
                        methodExecutor(network, "")
                    }

                    2 -> {
                        methodExecutor(network, NULL_PIN_IDENTIFIER)
                    }

                    3 -> {
                        if (!databasePin.isNullOrBlank() && databasePin != "0") {
                            methodExecutor(network, databasePin)
                        } else {
                            showPinInputDialog(network, methodExecutor)
                        }
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

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.wps_pin_input_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = editText.text.toString().trim()
                if (pin.isEmpty() || (pin.length == 8 && pin.all { it.isDigit() })) {
                    methodExecutor(network, pin.ifEmpty { "" })
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