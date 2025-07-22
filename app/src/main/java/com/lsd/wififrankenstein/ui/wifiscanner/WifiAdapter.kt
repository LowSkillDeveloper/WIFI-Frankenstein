package com.lsd.wififrankenstein.ui.wifiscanner

import android.Manifest
import android.R.attr.entries
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WpsInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemCredentialBinding
import com.lsd.wififrankenstein.databinding.ItemWifiBinding
import java.util.Locale
import com.lsd.wififrankenstein.util.NetworkDetailsExtractor
import com.lsd.wififrankenstein.util.NetworkProtocol
import com.lsd.wififrankenstein.util.calculateDistanceString
import com.lsd.wififrankenstein.util.NetworkSecurityInfo
import com.lsd.wififrankenstein.util.NetworkFrequencyBand
import com.lsd.wififrankenstein.util.NetworkBandwidth
import com.lsd.wififrankenstein.util.SecurityProtocol

class WifiAdapter(private var wifiList: List<ScanResult>) :
    RecyclerView.Adapter<WifiAdapter.WifiViewHolder>() {

    private var onItemClickListener: ((View, ScanResult) -> Unit)? = null
    private var databaseResults: Map<String, List<NetworkDatabaseResult>> = emptyMap()

    fun setOnItemClickListener(listener: (View, ScanResult) -> Unit) {
        this.onItemClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WifiViewHolder {
        val binding = ItemWifiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WifiViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WifiViewHolder, position: Int) {
        holder.bind(wifiList[position])
    }

    override fun getItemCount() = wifiList.size

    fun updateData(newWifiList: List<ScanResult>) {
        val sortedNewWifiList = newWifiList.sortedByDescending { it.level }
        val diffCallback = WifiDiffCallback(wifiList, sortedNewWifiList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        wifiList = sortedNewWifiList
        diffResult.dispatchUpdatesTo(this)
    }

    fun updateDatabaseResults(results: Map<String, List<NetworkDatabaseResult>>) {
        databaseResults = results.mapKeys { (key, _) -> key.lowercase(Locale.ROOT) }
        notifyDataSetChanged()
    }

    fun getWifiList() = wifiList

    inner class WifiViewHolder(private val binding: ItemWifiBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val credentialsAdapter = CredentialsAdapter()
        private var isExpanded = false
        private var showAllCredentials = false
        private var fullResultsList: List<NetworkDatabaseResult> = emptyList()

        private val securityIcon = binding.securityIcon
        private val distanceTextView = binding.distanceTextView

        init {
            itemView.setOnClickListener { view ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClickListener?.invoke(view, wifiList[position])
                }
            }

            binding.expandButton.setOnClickListener {
                isExpanded = !isExpanded
                showAllCredentials = false
                updateExpandButtonIcon()
                updateCredentialsVisibility()
            }

            binding.expandAllButton.setOnClickListener {
                showAllCredentials = true
                updateCredentialsVisibility()
                Log.d("WifiAdapter", "Expand all button clicked")
            }

            binding.credentialsRecyclerView.layoutManager = LinearLayoutManager(itemView.context)
            binding.credentialsRecyclerView.adapter = credentialsAdapter
        }

        fun bind(scanResult: ScanResult) {
            binding.apply {
                val networkDetails = NetworkDetailsExtractor.extractDetails(scanResult)

                ssidTextView.text = scanResult.SSID
                bssidTextView.text = scanResult.BSSID
                levelTextView.text = "${scanResult.level} dBm"

                // Calculate and show distance
                val distance = calculateDistanceString(scanResult.frequency, scanResult.level, 1.0)
                distanceTextView.text = distance

                // Set security icon
                securityIcon.setImageResource(networkDetails.security.mainProtocol.iconRes)

                // Setup info badges
                binding.channelInfo.text = itemView.context.getString(R.string.channel_format, networkDetails.channel)
                binding.frequencyInfo.text = itemView.context.getString(networkDetails.frequencyBand.displayNameRes)
                binding.bandwidthInfo.text = itemView.context.getString(networkDetails.bandwidth.displayNameRes)

// Protocol info
                if (networkDetails.protocol != NetworkProtocol.UNKNOWN) {
                    binding.protocolInfo.visibility = View.VISIBLE
                    binding.protocolInfo.text = itemView.context.getString(networkDetails.protocol.shortNameRes)
                } else {
                    binding.protocolInfo.visibility = View.GONE
                }

// Security info
                binding.securityInfo.text = networkDetails.security.getSecurityString()

// WPS info
                if (networkDetails.security.hasWps) {
                    binding.wpsInfo.visibility = View.VISIBLE
                    binding.wpsInfo.text = "WPS"
                } else {
                    binding.wpsInfo.visibility = View.GONE
                }

                val networkResults = databaseResults[scanResult.BSSID.lowercase(Locale.ROOT)]

                if (!networkResults.isNullOrEmpty()) {
                    fullResultsList = networkResults
                    expandButton.visibility = View.VISIBLE
                    expandButton.text = itemView.context.getString(R.string.show_database_info)
                    startIcon.visibility = View.VISIBLE
                    endIcon.visibility = View.VISIBLE

                    val dbCount = networkResults.map { it.databaseName }.distinct().size
                    recordsCountTextView.visibility = View.VISIBLE
                    recordsCountTextView.text = itemView.context.getString(R.string.records_found, networkResults.size, dbCount)

                    updateCredentialsVisibility()
                } else {
                    expandButton.visibility = View.GONE
                    startIcon.visibility = View.GONE
                    endIcon.visibility = View.GONE
                    recordsCountTextView.visibility = View.GONE
                    credentialsRecyclerView.visibility = View.GONE
                    expandAllButton.visibility = View.GONE
                }
                updateExpandButtonIcon()
            }
        }

        private fun updateExpandButtonIcon() {
            val iconRes = if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            binding.startIcon.setImageResource(iconRes)
            binding.endIcon.setImageResource(iconRes)
        }

        private fun updateCredentialsVisibility() {
            Log.d("WifiAdapter", "Total credentials: ${fullResultsList.size}, isExpanded: $isExpanded, showAllCredentials: $showAllCredentials")
            if (!isExpanded) {
                binding.credentialsRecyclerView.visibility = View.GONE
                binding.expandAllButton.visibility = View.GONE
            } else {
                val results = if (showAllCredentials) fullResultsList else fullResultsList.take(2)
                binding.credentialsRecyclerView.visibility = View.VISIBLE
                if (showAllCredentials || fullResultsList.size <= 2) {
                    binding.expandAllButton.visibility = View.GONE
                    Log.d("WifiAdapter", "Showing all credentials")
                } else {
                    binding.expandAllButton.visibility = View.VISIBLE
                    Log.d("WifiAdapter", "Showing first 2 credentials")
                }
                credentialsAdapter.submitList(results)
            }
        }
    }

    private fun openMapWithCoordinates(context: Context, ssid: String, lat: Double, lon: Double) {
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(ssid)})")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri)

        if (mapIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(Intent.createChooser(mapIntent,
                context.getString(R.string.map_choose_app)))
        } else {
            val browserUri = Uri.parse("https://maps.google.com/maps?q=$lat,$lon")
            val browserIntent = Intent(Intent.ACTION_VIEW, browserUri)
            context.startActivity(browserIntent)
        }
    }

    private inner class CredentialsAdapter :
        ListAdapter<NetworkDatabaseResult, CredentialsAdapter.CredentialsViewHolder>(
            CredentialsDiffCallback()
        ) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CredentialsViewHolder {
            val binding =
                ItemCredentialBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return CredentialsViewHolder(binding)
        }

        override fun onBindViewHolder(holder: CredentialsViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class CredentialsViewHolder(private val binding: ItemCredentialBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(result: NetworkDatabaseResult) {
                Log.d("CredentialsAdapter", "Binding credentials for ${result.network.SSID}")
                Log.d("CredentialsAdapter", "Database info: ${result.databaseInfo}")

                val wifiKey = result.databaseInfo["WiFiKey"]?.toString()
                    ?: result.databaseInfo["wifi_pass"]?.toString()
                    ?: result.databaseInfo["key"]?.toString()
                    ?: itemView.context.getString(R.string.not_available)

                val wpsPin = result.databaseInfo["WPSPIN"]?.toString()
                    ?: result.databaseInfo["wps_pin"]?.toString()
                    ?: result.databaseInfo["wps"]?.toString()
                    ?: itemView.context.getString(R.string.not_available)

                binding.wifiKeyTextView.text = itemView.context.getString(R.string.wifi_key_format, wifiKey)
                binding.wpsPinTextView.text = itemView.context.getString(R.string.wps_pin_format, wpsPin)

                val hasKeyOrWps = wifiKey != itemView.context.getString(R.string.not_available) ||
                        wpsPin != itemView.context.getString(R.string.not_available)
                binding.actionsButton.visibility = if (hasKeyOrWps) View.VISIBLE else View.GONE

                binding.actionsButton.setOnClickListener {
                    showActionsMenu(it, result)
                }

                binding.mapButton.setOnClickListener {
                    val latitude = result.databaseInfo["lat"] as? Double
                        ?: result.databaseInfo["latitude"] as? Double
                    val longitude = result.databaseInfo["lon"] as? Double
                        ?: result.databaseInfo["longitude"] as? Double

                    if (latitude != null && longitude != null && (latitude != 0.0 || longitude != 0.0)) {
                        openMapWithCoordinates(itemView.context, result.network.SSID, latitude, longitude)
                    } else {
                        Toast.makeText(itemView.context,
                            itemView.context.getString(R.string.map_no_location),
                            Toast.LENGTH_SHORT).show()
                    }
                }

                binding.infoButton.setOnClickListener {
                    showAdditionalInfo(itemView.context, result)
                }
            }
        }

        private fun showActionsMenu(view: View, result: NetworkDatabaseResult) {
            val popupMenu = PopupMenu(view.context, view)
            popupMenu.menuInflater.inflate(R.menu.credential_actions, popupMenu.menu)

            val wifiKey = result.databaseInfo["WiFiKey"] as? String
                ?: result.databaseInfo["key"] as? String
                ?: ""
            val wpsPin = result.databaseInfo["WPSPIN"]?.toString()
                ?: result.databaseInfo["wps"]?.toString()
                ?: ""

            val prefs = view.context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val isRootEnabled = prefs.getBoolean("enable_root", false)
            popupMenu.menu.findItem(R.id.action_connect_wps_root).isVisible = isRootEnabled

            popupMenu.menu.findItem(R.id.action_copy_wifi_key).isEnabled = wifiKey.isNotEmpty()
            popupMenu.menu.findItem(R.id.action_copy_wps_pin).isEnabled = wpsPin.isNotEmpty()

            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_copy_wifi_key -> copyToClipboard(view.context, "WiFi Key", wifiKey)
                    R.id.action_copy_wps_pin -> copyToClipboard(view.context, "WPS PIN", wpsPin)
                    R.id.action_connect_wps -> {
                        val wifiManager = view.context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val wpsConfig = WpsInfo().apply {
                            setup = WpsInfo.KEYPAD
                            pin = wpsPin
                            BSSID = result.network.BSSID
                        }

                        if (ContextCompat.checkSelfPermission(view.context, Manifest.permission.CHANGE_WIFI_STATE) ==
                            PackageManager.PERMISSION_GRANTED) {

                            wifiManager.startWps(wpsConfig, object : WifiManager.WpsCallback() {
                                override fun onStarted(pin: String?) {
                                    Toast.makeText(view.context,
                                        view.context.getString(R.string.wps_started),
                                        Toast.LENGTH_SHORT).show()
                                }

                                override fun onSucceeded() {
                                    Toast.makeText(view.context,
                                        view.context.getString(R.string.wps_succeeded),
                                        Toast.LENGTH_SHORT).show()
                                }

                                override fun onFailed(reason: Int) {
                                    Toast.makeText(view.context,
                                        view.context.getString(R.string.wps_failed, reason),
                                        Toast.LENGTH_SHORT).show()
                                }
                            })
                        } else {
                            Toast.makeText(view.context,
                                view.context.getString(R.string.change_wifi_state_permission_required),
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                    R.id.action_save_profile -> {
                        val capabilities = result.network.capabilities
                        val ssid = result.network.SSID
                        val password = result.databaseInfo["WiFiKey"] as? String
                            ?: result.databaseInfo["key"] as? String

                        if (password.isNullOrEmpty()) {
                            Toast.makeText(view.context,
                                view.context.getString(R.string.toast_no_data_to_save),
                                Toast.LENGTH_SHORT).show()
                            return@setOnMenuItemClickListener true
                        }

                        saveWifiProfile(view.context, result.network, capabilities, ssid, password)
                    }
                }
                true
            }

            popupMenu.show()
        }

        private fun saveWifiProfile(
            context: Context,
            network: ScanResult,
            capabilities: String,
            ssid: String,
            password: String
        ) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            var saved = false

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                try {
                    val wifiConfig = WifiConfiguration().apply {
                        BSSID = network.BSSID
                        SSID = "\"$ssid\""
                        hiddenSSID = false
                        priority = 1000

                        when {
                            capabilities.contains("WEP") -> {
                                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                                allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                                allowedProtocols.set(WifiConfiguration.Protocol.WPA)
                                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                                wepKeys[0] = "\"$password\""
                                wepTxKeyIndex = 0
                            }
                            capabilities.contains("WPA") || capabilities.contains("PSK") -> {
                                preSharedKey = "\"$password\""
                            }
                        }
                    }

                    val netId = wifiManager.addNetwork(wifiConfig)
                    saved = netId > -1

                    if (saved) {
                        Toast.makeText(context, context.getString(R.string.wifi_network_saved), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("WifiAdapter", "Error saving network with old method", e)
                }
            }

            if (!saved && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val suggestionBuilder = WifiNetworkSuggestion.Builder()
                        .setSsid(ssid)

                    when {
                        capabilities.contains("WEP") -> {
                            Toast.makeText(context, context.getString(R.string.wifi_network_save_failed), Toast.LENGTH_SHORT).show()
                        }
                        capabilities.contains("WPA") || capabilities.contains("PSK") -> {
                            suggestionBuilder.setWpa2Passphrase(password)
                        }
                        capabilities.contains("WPA3") -> {
                            suggestionBuilder.setWpa3Passphrase(password)
                        }
                        else -> {
                        }
                    }

                    val suggestion = suggestionBuilder.build()
                    val suggestions = listOf(suggestion)

                    val status = wifiManager.addNetworkSuggestions(suggestions)

                    if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                        Toast.makeText(context, context.getString(R.string.wifi_suggestion_added), Toast.LENGTH_SHORT).show()
                        saved = true
                    } else {
                        Toast.makeText(context, context.getString(R.string.wifi_suggestion_failed), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("WifiAdapter", "Error saving network with new method", e)
                }
            }

            showWifiInfoDialog(context, ssid, password)
        }

        private fun showWifiInfoDialog(context: Context, ssid: String, password: String) {
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_wifi_info, null)
            val ssidTextView = dialogView.findViewById<TextView>(R.id.ssidTextView)
            val passwordTextView = dialogView.findViewById<TextView>(R.id.passwordTextView)
            val copySsidButton = dialogView.findViewById<Button>(R.id.copySsidButton)
            val copyPasswordButton = dialogView.findViewById<Button>(R.id.copyPasswordButton)
            val openWifiSettingsButton = dialogView.findViewById<Button>(R.id.openWifiSettingsButton)

            ssidTextView.text = context.getString(R.string.wifi_profile_ssid, ssid)
            passwordTextView.text = context.getString(R.string.wifi_profile_password, password)

            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.wifi_profile_info))
                .setView(dialogView)
                .setCancelable(true)
                .setPositiveButton(context.getString(R.string.close), null)
                .create()

            copySsidButton.setOnClickListener {
                copyToClipboard(context, context.getString(R.string.ssid), ssid)
            }

            copyPasswordButton.setOnClickListener {
                copyToClipboard(context, context.getString(R.string.password), password)
            }

            openWifiSettingsButton.setOnClickListener {
                val wifiSettingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
                context.startActivity(wifiSettingsIntent)
            }

            dialog.show()
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        private fun showAdditionalInfo(context: Context, result: NetworkDatabaseResult) {
            val ssid = result.databaseInfo["essid"] as? String
                ?: result.databaseInfo["ESSID"] as? String
                ?: "N/A"

            val message = buildString {
                append("SSID: $ssid\n")
                append("BSSID: ${result.network.BSSID}\n")
                append("Security: ${result.databaseInfo["sec"] ?: "N/A"}\n")
                append("Time: ${result.databaseInfo["time"] ?: "N/A"}\n")
                append("Latitude: ${result.databaseInfo["lat"] ?: "N/A"}\n")
                append("Longitude: ${result.databaseInfo["lon"] ?: "N/A"}\n")
                append("Database: ${extractDatabaseName(result.databaseName)}\n")
            }
            AlertDialog.Builder(context)
                .setTitle("Additional Information")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    class WifiDiffCallback(
        private val oldList: List<ScanResult>,
        private val newList: List<ScanResult>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition].BSSID.lowercase(Locale.ROOT) == newList[newItemPosition].BSSID.lowercase(Locale.ROOT)

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition] == newList[newItemPosition]
    }

    companion object {

        fun extractDatabaseName(path: String): String {
            return try {
                when {
                    path.startsWith("content://") -> {
                        val decodedPath = Uri.decode(path)
                        decodedPath.substringAfterLast('/').substringBefore('?')
                    }
                    path.startsWith("file://") -> {
                        path.substringAfterLast('/')
                    }
                    else -> {
                        path.substringAfterLast('/')
                    }
                }
            } catch (e: Exception) {
                path
            }
        }
    }

    class CredentialsDiffCallback : DiffUtil.ItemCallback<NetworkDatabaseResult>() {
        override fun areItemsTheSame(
            oldItem: NetworkDatabaseResult,
            newItem: NetworkDatabaseResult
        ): Boolean {
            return oldItem.network.BSSID.lowercase(Locale.ROOT) == newItem.network.BSSID.lowercase(Locale.ROOT)
        }

        override fun areContentsTheSame(
            oldItem: NetworkDatabaseResult,
            newItem: NetworkDatabaseResult
        ): Boolean {
            return oldItem == newItem
        }
    }
}