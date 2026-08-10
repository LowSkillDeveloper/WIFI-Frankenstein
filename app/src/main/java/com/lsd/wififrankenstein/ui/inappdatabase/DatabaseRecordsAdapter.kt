package com.lsd.wififrankenstein.ui.inappdatabase

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WpsGeneratorActivity
import com.lsd.wififrankenstein.databinding.ItemDatabaseRecordBinding
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.util.BottomSheetMenu
import com.lsd.wififrankenstein.util.BottomSheetMenuItem
import com.lsd.wififrankenstein.util.QrNavigationHelper

class DatabaseRecordsAdapter(
    private val onItemClick: (WifiNetwork) -> Unit,
    private val onItemEdit: ((WifiNetwork) -> Unit)? = null,
    private val onItemDelete: ((WifiNetwork) -> Unit)? = null,
    private val onUploadTo3WiFi: ((WifiNetwork) -> Unit)? = null,
    private val onCheckWpaSec: ((WifiNetwork) -> Unit)? = null
) : PagingDataAdapter<WifiNetwork, DatabaseRecordsAdapter.ViewHolder>(WifiNetworkDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemDatabaseRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let { holder.bind(it) }
    }

    inner class ViewHolder(private val binding: ItemDatabaseRecordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(wifiNetwork: WifiNetwork) {
            binding.apply {
                textViewWifiName.text = wifiNetwork.wifiName
                textViewMacAddress.text = wifiNetwork.macAddress

                setupPasswordAndWps(wifiNetwork)
                setupLocationButton(wifiNetwork)

                root.setOnClickListener {
                    showOptionsMenu(wifiNetwork)
                }
                root.setOnLongClickListener {
                    onItemClick(wifiNetwork)
                    true
                }
            }
        }

        private fun setupPasswordAndWps(wifiNetwork: WifiNetwork) {
            binding.apply {
                val context = itemView.context

                if (!wifiNetwork.wifiPassword.isNullOrBlank()) {
                    val isValidPassword = wifiNetwork.wifiPassword.length >= 8
                    textViewPassword.text =
                        context.getString(R.string.password_format, wifiNetwork.wifiPassword)
                    textViewPassword.setTextColor(
                        if (isValidPassword) getThemeTextColor(context) else ContextCompat.getColor(
                            context,
                            R.color.error_red
                        )
                    )
                    textViewPassword.visibility = View.VISIBLE
                } else {
                    textViewPassword.text = context.getString(R.string.password_not_available)
                    textViewPassword.setTextColor(
                        ContextCompat.getColor(
                            context,
                            R.color.error_red
                        )
                    )
                    textViewPassword.visibility = View.VISIBLE
                }

                if (!wifiNetwork.wpsCode.isNullOrBlank()) {
                    val isValidWpsPin =
                        wifiNetwork.wpsCode.length == 8 && wifiNetwork.wpsCode.all { it.isDigit() }
                    textViewWpsPin.text =
                        context.getString(R.string.wps_pin_format, wifiNetwork.wpsCode)
                    textViewWpsPin.setTextColor(
                        if (isValidWpsPin) getThemeTextColor(context) else ContextCompat.getColor(
                            context,
                            R.color.error_red
                        )
                    )
                    textViewWpsPin.visibility = View.VISIBLE
                } else {
                    textViewWpsPin.text = context.getString(R.string.wps_pin_not_available)
                    textViewWpsPin.setTextColor(ContextCompat.getColor(context, R.color.error_red))
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
                val hasValidLocation =
                    wifiNetwork.latitude != null && wifiNetwork.longitude != null &&
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
            val context = itemView.context
            val hasPassword = !wifiNetwork.wifiPassword.isNullOrBlank()
            val hasWps = !wifiNetwork.wpsCode.isNullOrBlank()
            val hasAdminPanel = !wifiNetwork.adminPanel.isNullOrBlank()
            val hasLocation = wifiNetwork.latitude != null && wifiNetwork.longitude != null &&
                    wifiNetwork.latitude != 0.0 && wifiNetwork.longitude != 0.0
            val hasValidCredentials = QrNavigationHelper.hasValidCredentials(
                wifiNetwork.wifiPassword, wifiNetwork.wpsCode
            )
            val canUpload = hasPassword || hasWps

            val items = mutableListOf(
                BottomSheetMenuItem(
                    R.id.action_copy_ssid,
                    context.getString(R.string.copy_ssid),
                    R.drawable.ic_content_copy
                ),
                BottomSheetMenuItem(
                    R.id.action_copy_bssid,
                    context.getString(R.string.copy_bssid),
                    R.drawable.ic_content_copy
                ),
                BottomSheetMenuItem(
                    R.id.action_copy_password,
                    context.getString(R.string.copy_password),
                    R.drawable.ic_content_copy,
                    enabled = hasPassword,
                    visible = hasPassword
                ),
                BottomSheetMenuItem(
                    R.id.action_copy_wps,
                    context.getString(R.string.copy_wps_pin),
                    R.drawable.ic_content_copy,
                    enabled = hasWps,
                    visible = hasWps
                ),
                BottomSheetMenuItem(
                    R.id.action_copy_admin_panel,
                    context.getString(R.string.copy_admin_panel),
                    R.drawable.ic_web,
                    enabled = hasAdminPanel,
                    visible = hasAdminPanel
                ),
                BottomSheetMenuItem(
                    R.id.action_show_location,
                    context.getString(R.string.show_on_map),
                    R.drawable.ic_location,
                    visible = hasLocation
                ),
                BottomSheetMenuItem(
                    R.id.action_generate_qr,
                    context.getString(R.string.action_generate_qr),
                    R.drawable.ic_qr_code,
                    visible = hasValidCredentials
                ),
                BottomSheetMenuItem(
                    R.id.action_generate_wps,
                    context.getString(R.string.generate_wps_pins),
                    R.drawable.ic_key
                ),
                BottomSheetMenuItem(
                    R.id.action_upload_3wifi,
                    context.getString(R.string.upload_to_3wifi),
                    R.drawable.ic_file_upload,
                    visible = canUpload
                ),
                BottomSheetMenuItem(
                    R.id.action_check_wpasec,
                    context.getString(R.string.wpasec_check),
                    R.drawable.cloud_24px
                ),
                BottomSheetMenuItem(
                    R.id.action_edit,
                    context.getString(R.string.edit_record),
                    R.drawable.ic_settings,
                    visible = onItemEdit != null
                ),
                BottomSheetMenuItem(
                    R.id.action_delete,
                    context.getString(R.string.delete_record),
                    R.drawable.delete_forever_24px,
                    visible = onItemDelete != null
                )
            )

            BottomSheetMenu.show(context, items = items) { menuItem ->
                when (menuItem.id) {
                    R.id.action_copy_ssid -> copyToClipboard(context, "SSID", wifiNetwork.wifiName)
                    R.id.action_copy_bssid -> copyToClipboard(
                        context,
                        "BSSID",
                        wifiNetwork.macAddress
                    )

                    R.id.action_copy_password -> wifiNetwork.wifiPassword?.let {
                        copyToClipboard(
                            context,
                            "Password",
                            it
                        )
                    }

                    R.id.action_copy_wps -> wifiNetwork.wpsCode?.let {
                        copyToClipboard(
                            context,
                            "WPS PIN",
                            it
                        )
                    }

                    R.id.action_copy_admin_panel -> wifiNetwork.adminPanel?.let {
                        copyToClipboard(
                            context,
                            "Admin Panel",
                            it
                        )
                    }

                    R.id.action_show_location -> openLocation(wifiNetwork)
                    R.id.action_generate_wps -> openWpsGenerator(wifiNetwork.macAddress)
                    R.id.action_generate_qr -> {
                        val activity = context as? FragmentActivity
                        val fragment =
                            activity?.supportFragmentManager?.fragments?.find { it.isVisible }
                        if (fragment != null) {
                            val password = wifiNetwork.wifiPassword ?: ""
                            val security = if (password.isNotEmpty()) "WPA" else "NONE"
                            QrNavigationHelper.navigateToQrGenerator(
                                fragment,
                                wifiNetwork.wifiName,
                                password,
                                security
                            )
                        }
                    }

                    R.id.action_upload_3wifi -> onUploadTo3WiFi?.invoke(wifiNetwork)
                    R.id.action_check_wpasec -> onCheckWpaSec?.invoke(wifiNetwork)
                    R.id.action_edit -> onItemEdit?.invoke(wifiNetwork)
                    R.id.action_delete -> onItemDelete?.invoke(wifiNetwork)
                }
            }
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = ContextCompat.getSystemService(context, ClipboardManager::class.java)
            val clip = ClipData.newPlainText(label, text)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(
                context,
                context.getString(R.string.copied_to_clipboard, label),
                Toast.LENGTH_SHORT
            ).show()
        }

        private fun openLocation(wifiNetwork: WifiNetwork) {
            if (wifiNetwork.latitude != null && wifiNetwork.longitude != null && wifiNetwork.latitude != 0.0 && wifiNetwork.longitude != 0.0) {
                val uri =
                    "geo:${wifiNetwork.latitude},${wifiNetwork.longitude}?q=${wifiNetwork.latitude},${wifiNetwork.longitude}(${
                        Uri.encode(wifiNetwork.wifiName)
                    })".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri)
                itemView.context.startActivity(
                    Intent.createChooser(
                        intent,
                        itemView.context.getString(R.string.map_choose_app)
                    )
                )
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