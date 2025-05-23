package com.lsd.wififrankenstein

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class PinListAdapter : ListAdapter<WPSPin, PinListAdapter.PinViewHolder>(PinDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PinViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pin, parent, false)
        return PinViewHolder(view)
    }

    override fun onBindViewHolder(holder: PinViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PinViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textPin: TextView = itemView.findViewById(R.id.text_pin)
        private val textAlgo: TextView = itemView.findViewById(R.id.text_algo)
        private val textScore: TextView = itemView.findViewById(R.id.text_score)
        private val textDb: TextView = itemView.findViewById(R.id.text_db)
        private val textAdditionalData: TextView = itemView.findViewById(R.id.text_additional_data)

        fun bind(pin: WPSPin) {
            Log.d("PinViewHolder", "Binding pin: ${pin.pin}, name: ${pin.name}, sugg: ${pin.sugg}, score: ${pin.score}")
            textPin.text = pin.pin
            textAlgo.text = pin.name

            if (pin.isFrom3WiFi) {
                textScore.visibility = View.VISIBLE
                textScore.text = "Score: %.2f".format(pin.score)
            } else {
                textScore.visibility = View.GONE
            }

            textDb.text = if (pin.sugg) "✔" else ""

            val additionalDataString = pin.additionalData.entries.joinToString("\n") { (key, value) ->
                "$key: $value"
            }
            textAdditionalData.text = additionalDataString
            textAdditionalData.visibility = if (additionalDataString.isNotEmpty()) View.VISIBLE else View.GONE

            val context = itemView.context
            val typedValue = TypedValue()

            context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            val primaryTextColor = ContextCompat.getColor(context, typedValue.resourceId)

            context.theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
            val secondaryTextColor = ContextCompat.getColor(context, typedValue.resourceId)

            textPin.setTextColor(primaryTextColor)
            textAlgo.setTextColor(secondaryTextColor)
            textScore.setTextColor(secondaryTextColor)
            textDb.setTextColor(secondaryTextColor)
            textAdditionalData.setTextColor(secondaryTextColor)

            itemView.setOnClickListener { view ->
                showPopupMenu(view, pin)
            }
        }

        private fun showPopupMenu(view: View, pin: WPSPin) {
            PopupMenu(view.context, view).apply {
                menuInflater.inflate(R.menu.pin_actions, menu)

                // Проверяем включены ли root функции
                val prefs = view.context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val isRootEnabled = prefs.getBoolean("enable_root", false)
                menu.findItem(R.id.action_connect_wps_root).isVisible = isRootEnabled

                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_connect_wps -> {
                            connectUsingWPS(view.context, pin)
                            true
                        }
                        R.id.action_connect_wps_root -> {
                            connectUsingWPSRoot(view.context, pin)
                            true
                        }
                        R.id.action_copy_pin -> {
                            copyPinToClipboard(view.context, pin)
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }

        private fun connectUsingWPS(context: Context, pin: WPSPin) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_STATE)
                == PackageManager.PERMISSION_GRANTED) {

                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager

                val wpsConfig = WpsInfo().apply {
                    setup = WpsInfo.KEYPAD
                    this.pin = pin.pin
                }

                wifiManager.startWps(wpsConfig, object : WifiManager.WpsCallback() {
                    override fun onStarted(pin: String?) {
                        Toast.makeText(context,
                            context.getString(R.string.wps_started),
                            Toast.LENGTH_SHORT).show()
                    }

                    override fun onSucceeded() {
                        Toast.makeText(context,
                            context.getString(R.string.wps_succeeded),
                            Toast.LENGTH_SHORT).show()
                    }

                    override fun onFailed(reason: Int) {
                        Toast.makeText(context,
                            context.getString(R.string.wps_failed, reason),
                            Toast.LENGTH_SHORT).show()
                    }
                })
            } else {
                Toast.makeText(context,
                    context.getString(R.string.change_wifi_state_permission_required),
                    Toast.LENGTH_SHORT).show()
            }
        }

        private fun connectUsingWPSRoot(context: Context, pin: WPSPin) {
            Toast.makeText(context,
                context.getString(R.string.root_wps_not_implemented),
                Toast.LENGTH_SHORT).show()
        }

        private fun copyPinToClipboard(context: Context, pin: WPSPin) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("WPS PIN", pin.pin)
            clipboard.setPrimaryClip(clip)

            Toast.makeText(context,
                context.getString(R.string.copied_to_clipboard, "WPS PIN"),
                Toast.LENGTH_SHORT).show()
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