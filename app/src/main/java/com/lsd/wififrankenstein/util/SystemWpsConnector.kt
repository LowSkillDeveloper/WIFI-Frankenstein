package com.lsd.wififrankenstein.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.WpsMethodSelector.Companion.NULL_PIN_IDENTIFIER














class SystemWpsConnector(private val context: Context) {

    interface WpsCallbacks {
        fun onStarted(pin: String?)
        fun onSucceeded()
        fun onFailed(reason: Int)
        fun onError(message: String)
    }

    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isSupported(): Boolean {
        val result = Build.VERSION.SDK_INT < Build.VERSION_CODES.P
        Log.d(
            TAG,
            "isSupported: sdk=${Build.VERSION.SDK_INT} (P=${Build.VERSION_CODES.P}) result=$result"
        )
        return result
    }

    fun hasPermission(): Boolean {
        val result =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_STATE) ==
                    PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "hasPermission: CHANGE_WIFI_STATE granted=$result")
        return result
    }

    fun connect(bssid: String, wpsPin: String?, callbacks: WpsCallbacks) {
        val modeName = when {
            wpsPin == null -> "PBC"
            wpsPin == NULL_PIN_IDENTIFIER -> "NULL_PIN"
            wpsPin == "" -> "EMPTY_PIN"
            else -> "REAL_PIN"
        }
        Log.d(
            TAG,
            "connect: entry bssid='$bssid' wpsPin='${wpsPin.orEmpty()}' mode=$modeName " +
                    "sdk=${Build.VERSION.SDK_INT} wifiEnabled=${wifiManager.isWifiEnabled}"
        )

        if (!isSupported()) {
            Log.w(TAG, "connect: WPS not supported on this build, aborting")
            mainHandler.post { callbacks.onError(context.getString(R.string.wps_not_supported)) }
            return
        }
        if (!hasPermission()) {
            Log.w(TAG, "connect: CHANGE_WIFI_STATE permission missing, aborting")
            mainHandler.post {
                callbacks.onError(context.getString(R.string.change_wifi_state_permission_required))
            }
            return
        }

        val wpsConfig = WpsInfo().apply {
            when {
                wpsPin == null -> setup = WpsInfo.PBC
                wpsPin == NULL_PIN_IDENTIFIER -> {
                    setup = WpsInfo.KEYPAD
                    pin = null
                }

                else -> {
                    setup = WpsInfo.KEYPAD
                    pin = wpsPin
                }
            }
            BSSID = bssid
        }

        Log.d(
            TAG,
            "connect: WpsInfo setup=${wpsConfig.setup} " +
                    "(PBC=${WpsInfo.PBC}, KEYPAD=${WpsInfo.KEYPAD}, LABEL=${WpsInfo.LABEL}, DISPLAY=${WpsInfo.DISPLAY}) " +
                    "pin='${wpsConfig.pin.orEmpty()}' bssid='${wpsConfig.BSSID}' mode=$modeName"
        )

        try {
            val startTime = System.currentTimeMillis()
            wifiManager.startWps(wpsConfig, object : WifiManager.WpsCallback() {
                override fun onStarted(pin: String?) {
                    Log.d(
                        TAG,
                        "connect: WpsCallback.onStarted pin='${pin.orEmpty()}' elapsed=${System.currentTimeMillis() - startTime}ms"
                    )
                    mainHandler.post { callbacks.onStarted(pin) }
                }

                override fun onSucceeded() {
                    Log.d(
                        TAG,
                        "connect: WpsCallback.onSucceeded elapsed=${System.currentTimeMillis() - startTime}ms"
                    )
                    mainHandler.post { callbacks.onSucceeded() }
                }

                override fun onFailed(reason: Int) {
                    Log.w(
                        TAG,
                        "connect: WpsCallback.onFailed reason=$reason elapsed=${System.currentTimeMillis() - startTime}ms"
                    )
                    mainHandler.post { callbacks.onFailed(reason) }
                }
            })
            Log.d(TAG, "connect: startWps called successfully, waiting for callbacks")
        } catch (e: Exception) {
            Log.e(TAG, "connect: startWps failed", e)
            mainHandler.post {
                callbacks.onError(
                    e.message ?: context.getString(R.string.wps_connection_error)
                )
            }
        }
    }

    companion object {
        private const val TAG = "SystemWpsConnector"







        fun showModeSelection(
            context: Context,
            databasePin: String? = null,
            onConnect: (String?) -> Unit
        ) {
            val options = mutableListOf<String>()
            options.add(context.getString(R.string.wps_mode_pbc))
            options.add(context.getString(R.string.wps_use_empty_pin))
            options.add(context.getString(R.string.wps_use_null_pin))

            val hasDatabasePin = !databasePin.isNullOrBlank() && databasePin != "0"
            if (hasDatabasePin) {
                options.add(context.getString(R.string.wps_use_database_pin, databasePin))
            }

            options.add(context.getString(R.string.wps_enter_custom_pin))

            Log.d(
                TAG,
                "showModeSelection: options=${options.size} " +
                        "hasDatabasePin=$hasDatabasePin databasePin='${databasePin.orEmpty()}'"
            )

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.wps_mode_selection_title)
                .setItems(options.toTypedArray()) { _, which ->
                    val resolved = when (which) {
                        0 -> null
                        1 -> ""
                        2 -> NULL_PIN_IDENTIFIER
                        3 -> if (hasDatabasePin) databasePin else null
                        else -> null
                    }
                    val action =
                        if (which == 3 && !hasDatabasePin || which > 3) "showPinInputDialog" else "onConnect"
                    Log.d(
                        TAG,
                        "showModeSelection: user selected index=$which resolvedPin='${resolved.orEmpty()}' " +
                                "action=$action"
                    )
                    when (which) {
                        0 -> onConnect(null)
                        1 -> onConnect("")
                        2 -> onConnect(NULL_PIN_IDENTIFIER)
                        3 -> {
                            if (hasDatabasePin) {
                                onConnect(databasePin)
                            } else {
                                showPinInputDialog(context, onConnect)
                            }
                        }

                        else -> showPinInputDialog(context, onConnect)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showPinInputDialog(context: Context, onConnect: (String?) -> Unit) {
            Log.d(TAG, "showPinInputDialog: showing PIN input dialog")
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
                    Log.d(
                        TAG,
                        "showPinInputDialog: entered pin='$pin' len=${pin.length} " +
                                "isValid=${pin.isEmpty() || (pin.length == 8 && pin.all { it.isDigit() })}"
                    )
                    if (pin.isEmpty() || (pin.length == 8 && pin.all { it.isDigit() })) {
                        onConnect(pin)
                    } else {
                        Log.w(TAG, "showPinInputDialog: invalid pin format rejected: '$pin'")
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
    }
}
