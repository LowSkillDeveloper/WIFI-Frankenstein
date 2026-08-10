package com.lsd.wififrankenstein

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.util.BottomSheetMenu
import com.lsd.wififrankenstein.util.BottomSheetMenuItem
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.SystemWpsConnector
import com.lsd.wififrankenstein.util.WpsMethodSelector
import com.lsd.wififrankenstein.util.WpsRootConnectHelper

class PinListAdapter(
    bssid: String = ""
) : ListAdapter<WPSPin, PinListAdapter.PinViewHolder>(PinDiffCallback()) {

    private companion object {
        private const val TAG = "PinListAdapter"
    }

    var targetBssid: String = bssid


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PinViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pin, parent, false)
        return PinViewHolder(view)
    }

    override fun onBindViewHolder(holder: PinViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PinViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textPin: TextView = itemView.findViewById(R.id.text_pin)
        private val textAlgo: TextView = itemView.findViewById(R.id.text_algo)
        private val textScore: TextView = itemView.findViewById(R.id.text_score)
        private val textDb: TextView = itemView.findViewById(R.id.text_db)
        private val textAdditionalData: TextView = itemView.findViewById(R.id.text_additional_data)

        fun bind(pin: WPSPin) {
            Log.d(
                "PinViewHolder",
                "Binding pin: ${pin.pin}, name: ${pin.name}, sugg: ${pin.sugg}, score: ${pin.score}"
            )
            textPin.text = pin.pin

            if (pin.isFrom3WiFi) {
                textScore.visibility = View.VISIBLE
                textScore.text = itemView.context.getString(R.string.score_format, pin.score)
            } else {
                textScore.visibility = View.GONE
            }

            if (pin.sugg) {
                textDb.text = ""
                val starDrawable = ContextCompat.getDrawable(itemView.context, R.drawable.ic_star)
                starDrawable?.setBounds(
                    0,
                    0,
                    starDrawable.intrinsicWidth,
                    starDrawable.intrinsicHeight
                )
                textDb.setCompoundDrawablesRelative(starDrawable, null, null, null)
            } else {
                textDb.text = ""
                textDb.setCompoundDrawablesRelative(null, null, null, null)
            }

            val additionalInfo = when {
                pin.isFrom3WiFi -> {
                    val type = pin.additionalData["type"]?.toString() ?: ""
                    val database = pin.additionalData["database"]?.toString() ?: ""
                    val neighborBssid = pin.additionalData["neighbor_bssid"]?.toString()
                    val distance = pin.additionalData["distance"]?.toString()

                    when {
                        neighborBssid != null && distance != null ->
                            "$type • Neighbor: ${neighborBssid.takeLast(8)} (±$distance)"

                        neighborBssid != null ->
                            "$type • Neighbor: ${neighborBssid.takeLast(8)}"

                        database.isNotEmpty() -> "$type • $database"
                        else -> type
                    }
                }

                pin.additionalData.containsKey("source") -> {
                    val source = pin.additionalData["source"]?.toString() ?: ""
                    val type = pin.additionalData["type"]?.toString() ?: ""
                    val database = pin.additionalData["database"]?.toString() ?: ""
                    when {
                        database.isNotEmpty() -> "$type • $database"
                        type.isNotEmpty() -> type
                        else -> source
                    }
                }

                pin.additionalData.containsKey("mode") -> {
                    pin.additionalData["mode"]?.toString() ?: ""
                }

                else -> ""
            }
            textAdditionalData.text = additionalInfo
            textAdditionalData.visibility =
                if (additionalInfo.isNotEmpty()) View.VISIBLE else View.GONE

            val context = itemView.context
            val typedValue = TypedValue()

            context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            val primaryTextColor = ContextCompat.getColor(context, typedValue.resourceId)

            context.theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
            val secondaryTextColor = ContextCompat.getColor(context, typedValue.resourceId)

            if (pin.sugg) {
                val suggestedColor =
                    ContextCompat.getColor(context, android.R.color.holo_green_dark)
                textPin.setTextColor(suggestedColor)
                textAlgo.setTextColor(suggestedColor)
                textScore.setTextColor(suggestedColor)
                textDb.setTextColor(suggestedColor)
                textAdditionalData.setTextColor(suggestedColor)
            } else {
                textPin.setTextColor(primaryTextColor)
                textAlgo.setTextColor(secondaryTextColor)
                textScore.setTextColor(secondaryTextColor)
                textDb.setTextColor(secondaryTextColor)
                textAdditionalData.setTextColor(secondaryTextColor)
            }

            if (pin.isExperimental || pin.name.lowercase().contains("fake")) {
                textAlgo.setTextColor(ContextCompat.getColor(context, R.color.error_red))
            }

            if (pin.isExperimental && !pin.sugg) {
                textAlgo.text = "${pin.name}"
            } else {
                textAlgo.text = pin.name
            }

            itemView.setOnClickListener { view ->
                showPopupMenu(view, pin)
            }
        }

        private fun showPopupMenu(view: View, pin: WPSPin) {
            val context = view.context
            val prefs = context.getSharedPreferences(
                "com.lsd.wififrankenstein",
                Context.MODE_PRIVATE
            )
            val isRootEnabled = prefs.getBoolean("enable_root", false)

            val items = listOf(
                BottomSheetMenuItem(
                    R.id.action_connect_wps,
                    context.getString(R.string.connect_wps),
                    R.drawable.wifi_protected_setup_24px
                ),
                BottomSheetMenuItem(
                    R.id.action_connect_wps_root,
                    context.getString(R.string.connect_wps_root),
                    R.drawable.wifi_protected_setup_24px,
                    visible = isRootEnabled
                ),
                BottomSheetMenuItem(
                    R.id.action_copy_pin,
                    context.getString(R.string.copy_wps_pin),
                    R.drawable.ic_content_copy
                )
            )

            BottomSheetMenu.show(context, items = items) { menuItem ->
                when (menuItem.id) {
                    R.id.action_connect_wps -> connectUsingWPS(context, pin)
                    R.id.action_connect_wps_root -> connectUsingWPSRoot(context, pin)
                    R.id.action_copy_pin -> copyPinToClipboard(context, pin)
                }
            }
        }

        private fun connectUsingWPS(context: Context, pin: WPSPin) {
            Log.d(
                TAG,
                "connectUsingWPS: entry pin='${pin.pin}' name='${pin.name}' " +
                        "targetBssid='${this@PinListAdapter.targetBssid}' sdk=${Build.VERSION.SDK_INT}"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Log.w(TAG, "connectUsingWPS: WPS not supported on Android 9+, aborting")
                Toast.makeText(
                    context,
                    context.getString(R.string.wps_not_supported),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_STATE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "connectUsingWPS: CHANGE_WIFI_STATE permission missing, aborting")
                Toast.makeText(
                    context,
                    context.getString(R.string.change_wifi_state_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            val targetBssid = this@PinListAdapter.targetBssid
            if (targetBssid.isEmpty()) {
                Log.w(TAG, "connectUsingWPS: targetBssid is empty, aborting")
                Toast.makeText(
                    context,
                    context.getString(R.string.wps_connection_error),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            SystemWpsConnector.showModeSelection(context, databasePin = pin.pin) { wpsPin ->
                val modeName = when {
                    wpsPin == null -> "PBC"
                    wpsPin == "" -> "EMPTY_PIN"
                    wpsPin == WpsMethodSelector.NULL_PIN_IDENTIFIER -> "NULL_PIN"
                    else -> "REAL_PIN"
                }
                Log.d(
                    TAG,
                    "connectUsingWPS: mode selected mode=$modeName wpsPin='${wpsPin.orEmpty()}' " +
                            "targetBssid='$targetBssid'"
                )
                val connector = SystemWpsConnector(context)
                connector.connect(
                    targetBssid,
                    wpsPin,
                    object : SystemWpsConnector.WpsCallbacks {
                        override fun onStarted(startedPin: String?) {
                            val message = when {
                                wpsPin == null -> context.getString(R.string.wps_started_pbc)
                                wpsPin == "" -> context.getString(R.string.wps_started_empty_pin)
                                wpsPin == WpsMethodSelector.NULL_PIN_IDENTIFIER ->
                                    context.getString(R.string.wps_started_null_pin)

                                else -> context.getString(R.string.wps_started_with_pin, wpsPin)
                            }
                            Log.d(
                                TAG,
                                "connectUsingWPS: onStarted pin='${startedPin.orEmpty()}' message='$message'"
                            )
                            (context as? android.app.Activity)?.runOnUiThread {
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        }

                        override fun onSucceeded() {
                            Log.d(TAG, "connectUsingWPS: WPS succeeded for bssid='$targetBssid'")
                            (context as? android.app.Activity)?.runOnUiThread {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.wps_succeeded),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        override fun onFailed(reason: Int) {
                            Log.w(
                                TAG,
                                "connectUsingWPS: WPS failed reason=$reason for bssid='$targetBssid'"
                            )
                            (context as? android.app.Activity)?.runOnUiThread {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.wps_failed, reason),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        override fun onError(message: String) {
                            Log.e(TAG, "connectUsingWPS: WPS error: $message")
                            (context as? android.app.Activity)?.runOnUiThread {
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
        }

        private fun connectUsingWPSRoot(context: Context, pin: WPSPin) {
            try {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo

                val ssid = wifiInfo.ssid?.replace("\"", "") ?: ""
                val bssid = wifiInfo.bssid ?: ""
                if (ssid.isEmpty() || bssid.isEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.wps_root_not_connected),
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }

                val scanResult = createScanResult(ssid, bssid)
                val methodSelector = WpsMethodSelector(
                    context,
                    object : WpsRootConnectHelper.WpsConnectCallbacks {
                        override fun onConnectionProgress(message: String) {
                            if (context is android.app.Activity) {
                                context.runOnUiThread {
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        override fun onConnectionSuccess(ssid: String) {
                            if (context is android.app.Activity) {
                                context.runOnUiThread {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.wps_root_connection_successful,
                                            ssid
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }

                        override fun onConnectionFailed(error: String) {
                            if (context is android.app.Activity) {
                                context.runOnUiThread {
                                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                }
                            }
                        }

                        override fun onLogEntry(message: String) {
                            Log.d("PinListAdapter-WPS", message)
                        }

                        override fun onWpsResult(pin: String?, psk: String?) {
                            if (context is android.app.Activity) {
                                context.runOnUiThread {
                                    showWpsResultDialog(context, ssid, pin, psk)
                                }
                            }
                        }
                    }
                )
                methodSelector.showMethodSelection(scanResult, pin.pin.takeIf { it.isNotEmpty() })
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.wps_connection_error, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        private fun createScanResult(ssid: String, bssid: String): ScanResult {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
            val result = allocateInstance.invoke(unsafe, ScanResult::class.java) as ScanResult
            result.SSID = ssid
            result.BSSID = bssid
            result.capabilities = "[WPA2-PSK-CCMP][WPS]"
            return result
        }

        private fun copyPinToClipboard(context: Context, pin: WPSPin) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("WPS PIN", pin.pin)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(
                context,
                context.getString(R.string.copied_to_clipboard, "WPS PIN"),
                Toast.LENGTH_SHORT
            ).show()
        }

        private fun showWpsResultDialog(
            context: Context,
            ssid: String?,
            pin: String?,
            psk: String?
        ) {
            val pinText =
                pin?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.wps_result_none)
            val pskText =
                psk?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.wps_result_none)
            val ssidText =
                ssid?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.wps_result_unknown)

            val content = context.getString(R.string.wps_result_format, ssidText, pinText, pskText)
            val copyText = context.getString(
                R.string.wps_result_copy_format,
                ssidText,
                pinText,
                pskText
            )

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.wps_result_title)
                .setMessage(content)
                .setPositiveButton(R.string.wps_result_copy) { _, _ ->
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("WPS Result", copyText))
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.copied_to_clipboard,
                            context.getString(R.string.wps_result_title)
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        }
    }

    class PinDiffCallback : DiffUtil.ItemCallback<WPSPin>() {
        override fun areItemsTheSame(oldItem: WPSPin, newItem: WPSPin): Boolean {
            return oldItem.pin == newItem.pin
        }

        override fun areContentsTheSame(oldItem: WPSPin, newItem: WPSPin): Boolean {
            return oldItem == newItem
        }
    }
}