package com.lsd.wififrankenstein.ui.inappdatabase

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WpsGeneratorActivity
import com.lsd.wififrankenstein.databinding.ItemDatabaseRecordBinding
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork

class DatabaseRecordsAdapter(
    private val onItemClick: (WifiNetwork) -> Unit,
    private val onItemEdit: ((WifiNetwork) -> Unit)? = null,
    private val onItemDelete: ((WifiNetwork) -> Unit)? = null
) : PagingDataAdapter<WifiNetwork, DatabaseRecordsAdapter.ViewHolder>(WifiNetworkDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDatabaseRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let { holder.bind(it) }
    }

    inner class ViewHolder(private val binding: ItemDatabaseRecordBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(wifiNetwork: WifiNetwork) {
            binding.apply {
                textViewWifiName.text = wifiNetwork.wifiName
                textViewMacAddress.text = wifiNetwork.macAddress

                setupPasswordAndWps(wifiNetwork)
                setupLocationButton(wifiNetwork)

                root.setOnClickListener { onItemClick(wifiNetwork) }
                root.setOnLongClickListener {
                    showOptionsMenu(wifiNetwork)
                    true
                }
            }
        }

        private fun setupPasswordAndWps(wifiNetwork: WifiNetwork) {
            binding.apply {
                val context = itemView.context

                if (!wifiNetwork.wifiPassword.isNullOrBlank()) {
                    val isValidPassword = wifiNetwork.wifiPassword.length >= 8
                    textViewPassword.text = context.getString(R.string.password_format, wifiNetwork.wifiPassword)
                    textViewPassword.setTextColor(if (isValidPassword) getThemeTextColor(context) else Color.RED)
                    textViewPassword.visibility = View.VISIBLE
                } else {
                    textViewPassword.text = context.getString(R.string.password_not_available)
                    textViewPassword.setTextColor(Color.RED)
                    textViewPassword.visibility = View.VISIBLE
                }

                if (!wifiNetwork.wpsCode.isNullOrBlank()) {
                    val isValidWpsPin = wifiNetwork.wpsCode.length == 8 && wifiNetwork.wpsCode.all { it.isDigit() }
                    textViewWpsPin.text = context.getString(R.string.wps_pin_format, wifiNetwork.wpsCode)
                    textViewWpsPin.setTextColor(if (isValidWpsPin) getThemeTextColor(context) else Color.RED)
                    textViewWpsPin.visibility = View.VISIBLE
                } else {
                    textViewWpsPin.text = context.getString(R.string.wps_pin_not_available)
                    textViewWpsPin.setTextColor(Color.RED)
                    textViewWpsPin.visibility = View.VISIBLE
                }
            }
        }

        private fun getThemeTextColor(context: Context): Int {
            val typedValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.textColor, typedValue, true)
            return typedValue.data
        }

        private fun setupLocationButton(wifiNetwork: WifiNetwork) {
            binding.buttonLocation.apply {
                val hasValidLocation = wifiNetwork.latitude != null && wifiNetwork.longitude != null &&
                        wifiNetwork.latitude != 0.0 && wifiNetwork.longitude != 0.0

                isEnabled = hasValidLocation
                alpha = if (hasValidLocation) 1.0f else 0.5f

                if (hasValidLocation) {
                    setOnClickListener {
                        openLocation(wifiNetwork)
                    }
                } else {
                    setOnClickListener(null)
                }
            }
        }

        private fun showOptionsMenu(wifiNetwork: WifiNetwork) {
            val popup = PopupMenu(itemView.context, itemView)
            popup.inflate(R.menu.menu_database_record)

            popup.menu.findItem(R.id.action_copy_password)?.isVisible = !wifiNetwork.wifiPassword.isNullOrBlank()
            popup.menu.findItem(R.id.action_copy_wps)?.isVisible = !wifiNetwork.wpsCode.isNullOrBlank()
            popup.menu.findItem(R.id.action_copy_admin_panel)?.isVisible = !wifiNetwork.adminPanel.isNullOrBlank()
            popup.menu.findItem(R.id.action_show_location)?.isVisible = wifiNetwork.latitude != null && wifiNetwork.longitude != null && wifiNetwork.latitude != 0.0 && wifiNetwork.longitude != 0.0
            popup.menu.findItem(R.id.action_edit)?.isVisible = onItemEdit != null
            popup.menu.findItem(R.id.action_delete)?.isVisible = onItemDelete != null

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_copy_ssid -> {
                        copyToClipboard(itemView.context, "SSID", wifiNetwork.wifiName)
                        true
                    }
                    R.id.action_copy_bssid -> {
                        copyToClipboard(itemView.context, "BSSID", wifiNetwork.macAddress)
                        true
                    }
                    R.id.action_copy_password -> {
                        wifiNetwork.wifiPassword?.let {
                            copyToClipboard(itemView.context, "Password", it)
                        }
                        true
                    }
                    R.id.action_copy_wps -> {
                        wifiNetwork.wpsCode?.let {
                            copyToClipboard(itemView.context, "WPS PIN", it)
                        }
                        true
                    }
                    R.id.action_copy_admin_panel -> {
                        wifiNetwork.adminPanel?.let {
                            copyToClipboard(itemView.context, "Admin Panel", it)
                        }
                        true
                    }
                    R.id.action_show_location -> {
                        openLocation(wifiNetwork)
                        true
                    }
                    R.id.action_generate_wps -> {
                        openWpsGenerator(wifiNetwork.macAddress)
                        true
                    }
                    R.id.action_edit -> {
                        onItemEdit?.invoke(wifiNetwork)
                        true
                    }
                    R.id.action_delete -> {
                        onItemDelete?.invoke(wifiNetwork)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)
            val clip = ClipData.newPlainText(label, text)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.copied_to_clipboard, label), Toast.LENGTH_SHORT).show()
        }

        private fun openLocation(wifiNetwork: WifiNetwork) {
            if (wifiNetwork.latitude != null && wifiNetwork.longitude != null && wifiNetwork.latitude != 0.0 && wifiNetwork.longitude != 0.0) {
                val uri = "geo:${wifiNetwork.latitude},${wifiNetwork.longitude}?q=${wifiNetwork.latitude},${wifiNetwork.longitude}(${Uri.encode(wifiNetwork.wifiName)})".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri)
                itemView.context.startActivity(Intent.createChooser(intent, itemView.context.getString(R.string.map_choose_app)))
            }
        }

        private fun openWpsGenerator(bssid: String) {
            val intent = Intent(itemView.context, WpsGeneratorActivity::class.java).apply {
                putExtra("BSSID", bssid)
            }
            itemView.context.startActivity(intent)
        }
    }

    class WifiNetworkDiffCallback : DiffUtil.ItemCallback<WifiNetwork>() {
        override fun areItemsTheSame(oldItem: WifiNetwork, newItem: WifiNetwork): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: WifiNetwork, newItem: WifiNetwork): Boolean {
            return oldItem == newItem
        }
    }
}