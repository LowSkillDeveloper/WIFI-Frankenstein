package com.lsd.wififrankenstein.ui.wifiscanner

import android.Manifest
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
import com.lsd.wififrankenstein.databinding.ItemWpaResultBinding
import com.lsd.wififrankenstein.databinding.ItemWpsResultBinding
import com.lsd.wififrankenstein.util.NetworkDetailsExtractor
import com.lsd.wififrankenstein.util.NetworkProtocol
import com.lsd.wififrankenstein.util.QrNavigationHelper
import com.lsd.wififrankenstein.util.calculateDistanceString
import java.util.Locale

class WifiAdapter(private var wifiList: List<ScanResult>, private val context: Context) :
    RecyclerView.Adapter<WifiAdapter.WifiViewHolder>() {

    private var onItemClickListener: ((View, ScanResult) -> Unit)? = null
    private var databaseResults: Map<String, List<NetworkDatabaseResult>> = emptyMap()
    private var onScrollToTopListener: (() -> Unit)? = null

    private var isDatabaseResultsApplied = false
    private var networksWithDatabaseData = mutableSetOf<String>()

    fun setOnScrollToTopListener(listener: () -> Unit) {
        this.onScrollToTopListener = listener
    }

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
        isDatabaseResultsApplied = false
        networksWithDatabaseData.clear()
        val sortedNewWifiList = sortWifiList(newWifiList)
        val diffCallback = WifiDiffCallback(wifiList, sortedNewWifiList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        wifiList = sortedNewWifiList
        diffResult.dispatchUpdatesTo(this)
    }

    private fun sortWifiList(wifiList: List<ScanResult>): List<ScanResult> {
        if (wifiList.isEmpty()) {
            return wifiList
        }

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val shouldPrioritize = prefs.getBoolean("prioritize_networks_with_data", true)

        if (!shouldPrioritize || !isDatabaseResultsApplied || networksWithDatabaseData.isEmpty()) {
            return wifiList.sortedByDescending { it.level }
        }

        val networksWithData = mutableListOf<ScanResult>()
        val networksWithoutData = mutableListOf<ScanResult>()

        wifiList.forEach { network ->
            val bssid = network.BSSID.lowercase(Locale.ROOT)
            if (networksWithDatabaseData.contains(bssid)) {
                networksWithData.add(network)
            } else {
                networksWithoutData.add(network)
            }
        }

        val sortedNetworksWithData = networksWithData.sortedByDescending { it.level }
        val sortedNetworksWithoutData = networksWithoutData.sortedByDescending { it.level }

        android.util.Log.d("WifiAdapter", "Networks with data: ${sortedNetworksWithData.size}, without data: ${sortedNetworksWithoutData.size}")
        android.util.Log.d("WifiAdapter", "Networks with DB data BSSIDs: ${networksWithDatabaseData.joinToString()}")
        sortedNetworksWithData.forEach { network ->
            android.util.Log.d("WifiAdapter", "With data: ${network.SSID} (${network.BSSID}) - ${network.level} dBm")
        }

        return sortedNetworksWithData + sortedNetworksWithoutData
    }

    fun updateDatabaseResults(results: Map<String, List<NetworkDatabaseResult>>) {
        databaseResults = results.mapKeys { (key, _) -> key.lowercase(Locale.ROOT) }

        networksWithDatabaseData.clear()
        results.forEach { (bssid, networkResults) ->
            if (networkResults.isNotEmpty()) {
                networksWithDatabaseData.add(bssid.lowercase(Locale.ROOT))
            }
        }

        val hasNetworksWithData = networksWithDatabaseData.isNotEmpty()
        if (hasNetworksWithData) {
            isDatabaseResultsApplied = true
        }

        val sortedList = sortWifiList(wifiList)

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val shouldPrioritize = prefs.getBoolean("prioritize_networks_with_data", true)
        val shouldAutoScroll = prefs.getBoolean("auto_scroll_to_networks_with_data", true)

        if (shouldPrioritize && hasNetworksWithData) {
            wifiList = sortedList
            notifyDataSetChanged()

            if (shouldAutoScroll) {
                onScrollToTopListener?.invoke()
            }
        } else {
            val diffCallback = WifiDiffCallback(wifiList, sortedList)
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            wifiList = sortedList
            diffResult.dispatchUpdatesTo(this)
        }
    }

    fun clearDatabaseResults() {
        databaseResults = emptyMap()
        isDatabaseResultsApplied = false
        networksWithDatabaseData.clear()
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
            val networkDetails = NetworkDetailsExtractor.extractDetails(scanResult)

            binding.apply {
                ssidTextView.text = scanResult.SSID
                bssidTextView.text = scanResult.BSSID
                levelTextView.text = "${scanResult.level} dBm"

                val distance = calculateDistanceString(scanResult.frequency, scanResult.level, 1.0)
                distanceTextView.text = distance

                securityIcon.setImageResource(networkDetails.security.mainProtocol.iconRes)

                val capabilities = networkDetails.advancedCapabilities
                untrustedChip.visibility = if (capabilities.isUntrusted) View.VISIBLE else View.GONE

                channelInfo.text = itemView.context.getString(R.string.channel_format, networkDetails.channel)
                frequencyInfo.text = itemView.context.getString(networkDetails.frequencyBand.displayNameRes)
                bandwidthInfo.text = itemView.context.getString(networkDetails.bandwidth.displayNameRes)

                if (networkDetails.protocol != NetworkProtocol.UNKNOWN) {
                    protocolInfo.visibility = View.VISIBLE
                    protocolInfo.text = itemView.context.getString(networkDetails.protocol.shortNameRes)
                    protocolFullInfo.visibility = View.VISIBLE
                    protocolFullInfo.text = itemView.context.getString(networkDetails.protocol.fullNameRes)
                } else {
                    protocolInfo.visibility = View.GONE
                    protocolFullInfo.visibility = View.GONE
                }

                rttInfo.visibility = if (capabilities.supportsRtt) View.VISIBLE else View.GONE
                if (capabilities.supportsRtt) {
                    rttInfo.text = itemView.context.getString(R.string.wifi_rtt_responder)
                }

                ntbInfo.visibility = if (capabilities.supportsNtb) View.VISIBLE else View.GONE
                if (capabilities.supportsNtb) {
                    ntbInfo.text = itemView.context.getString(R.string.wifi_ntb_responder)
                }

                securityInfo.text = networkDetails.security.getSecurityString()

                val securityTypesText = networkDetails.security.getSecurityTypesString(itemView.context)
                if (securityTypesText.isNotBlank() && securityTypesText != itemView.context.getString(R.string.security_type_unknown)) {
                    securityTypesInfo.visibility = View.VISIBLE
                    securityTypesInfo.text = securityTypesText
                } else {
                    securityTypesInfo.visibility = View.GONE
                }

                if (networkDetails.security.hasWps) {
                    wpsInfo.visibility = View.VISIBLE
                    wpsInfo.text = "WPS"
                } else {
                    wpsInfo.visibility = View.GONE
                }

                if (networkDetails.security.isAdHoc) {
                    adhocInfo.visibility = View.VISIBLE
                    adhocInfo.text = "Ad-hoc"
                } else {
                    adhocInfo.visibility = View.GONE
                }

                val fastRoamingText = networkDetails.security.getFastRoamingString(itemView.context)
                if (fastRoamingText.isNotBlank()) {
                    fastRoamingInfo.visibility = View.VISIBLE
                    fastRoamingInfo.text = fastRoamingText
                } else {
                    fastRoamingInfo.visibility = View.GONE
                }

                twtInfo.visibility = if (capabilities.supportsTwt) View.VISIBLE else View.GONE
                if (capabilities.supportsTwt) {
                    twtInfo.text = itemView.context.getString(R.string.wifi_twt_responder)
                }

                mldInfo.visibility = if (capabilities.supportsMld) View.VISIBLE else View.GONE
                if (capabilities.supportsMld) {
                    mldInfo.text = itemView.context.getString(R.string.wifi_mld_support)
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
        ListAdapter<NetworkDatabaseResult, RecyclerView.ViewHolder>(
            CredentialsDiffCallback()
        ) {
        override fun getItemViewType(position: Int): Int {
            return when (getItem(position).resultType) {
                ResultType.DATABASE -> TYPE_DATABASE
                ResultType.WPA_ALGORITHM -> TYPE_WPA
                ResultType.WPS_ALGORITHM -> TYPE_WPS
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_DATABASE -> {
                    val binding = ItemCredentialBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                    CredentialsViewHolder(binding)
                }
                TYPE_WPA -> {
                    val binding = ItemWpaResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                    WpaViewHolder(binding)
                }
                TYPE_WPS -> {
                    val binding = ItemWpsResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                    WpsViewHolder(binding)
                }
                else -> throw IllegalArgumentException("Unknown view type: $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is CredentialsViewHolder -> holder.bind(getItem(position))
                is WpaViewHolder -> holder.bind(getItem(position))
                is WpsViewHolder -> holder.bind(getItem(position))
            }
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

        private fun generateQrCode(content: String): android.graphics.Bitmap? {
            return try {
                val writer = com.google.zxing.qrcode.QRCodeWriter()
                val hints = hashMapOf<com.google.zxing.EncodeHintType, Any>()
                hints[com.google.zxing.EncodeHintType.MARGIN] = 1

                val bitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, hints)
                val width = bitMatrix.width
                val height = bitMatrix.height
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)

                for (x in 0 until width) {
                    for (y in 0 until height) {
                        bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
                bitmap
            } catch (e: Exception) {
                null
            }
        }

        private fun showQrDialog(context: Context, ssid: String, qrBitmap: android.graphics.Bitmap) {
            val builder = AlertDialog.Builder(context)
            val imageView = android.widget.ImageView(context)
            imageView.setImageBitmap(qrBitmap)
            imageView.setPadding(32, 32, 32, 32)

            builder.setTitle(context.getString(R.string.qr_code_generated_for, ssid))
                .setView(imageView)
                .setPositiveButton(context.getString(R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                }
                .setNegativeButton(context.getString(R.string.save_to_gallery)) { _, _ ->
                    saveQrToGallery(context, qrBitmap, ssid)
                }
                .show()
        }

        private fun saveQrToGallery(context: Context, bitmap: android.graphics.Bitmap, ssid: String) {
            try {
                val filename = "wifi_qr_${ssid.replace("[^a-zA-Z0-9]".toRegex(), "_")}_${System.currentTimeMillis()}.png"

                val saved = android.provider.MediaStore.Images.Media.insertImage(
                    context.contentResolver,
                    bitmap,
                    filename,
                    context.getString(R.string.qr_code_for_wifi, ssid)
                )

                if (saved != null) {
                    Toast.makeText(context, context.getString(R.string.qr_saved_successfully), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.qr_save_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.qr_save_failed), Toast.LENGTH_SHORT).show()
            }
        }

        private fun showQrCode(context: Context, result: NetworkDatabaseResult) {
            try {
                val wifiKey = result.databaseInfo["WiFiKey"]?.toString()
                    ?: result.databaseInfo["wifi_pass"]?.toString()
                    ?: result.databaseInfo["key"]?.toString()
                    ?: ""

                val qrContent = if (wifiKey.isEmpty()) {
                    "WIFI:S:${result.network.SSID};T:nopass;;"
                } else {
                    val securityType = when {
                        result.network.capabilities.contains("WEP") -> "WEP"
                        result.network.capabilities.contains("WPA3") -> "WPA3"
                        else -> "WPA"
                    }
                    "WIFI:S:${result.network.SSID};T:$securityType;P:$wifiKey;;"
                }

                val qrBitmap = generateQrCode(qrContent)
                if (qrBitmap != null) {
                    showQrDialog(context, result.network.SSID, qrBitmap)
                } else {
                    Toast.makeText(context, context.getString(R.string.qr_code_generation_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.qr_code_generation_failed), Toast.LENGTH_SHORT).show()
            }
        }

        inner class WpaViewHolder(private val binding: ItemWpaResultBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(result: NetworkDatabaseResult) {
                val wpaResult = result.wpaResult ?: return

                binding.algorithmName.text = wpaResult.algorithm
                binding.wpaKeysText.text = wpaResult.keys.first()
                binding.generationTime.text = itemView.context.getString(R.string.generation_time, wpaResult.generationTime)

                binding.copyKeysButton.setOnClickListener {
                    copyToClipboard(itemView.context, "WPA Key", wpaResult.keys.first())
                }
            }
        }

        inner class WpsViewHolder(private val binding: ItemWpsResultBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(result: NetworkDatabaseResult) {
                val wpsPin = result.wpsPin ?: return

                binding.algorithmName.text = wpsPin.name
                binding.wpsPinText.text = wpsPin.pin

                val source = wpsPin.additionalData["source"] as? String
                val distance = wpsPin.additionalData["distance"] as? String

                val sourceText = when {
                    source == "neighbor_search" && distance != null ->
                        itemView.context.getString(R.string.source_format, "${wpsPin.name} (${distance} MAC distance)")
                    source != null ->
                        itemView.context.getString(R.string.source_format, source)
                    else -> ""
                }
                binding.sourceInfo.text = sourceText

                binding.scoreText.text = itemView.context.getString(R.string.score_format, wpsPin.score)

                if (wpsPin.sugg) {
                    binding.statusIcon.setImageResource(R.drawable.ic_star)
                    binding.statusIcon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.orange_dark))
                } else {
                    binding.statusIcon.setImageResource(R.drawable.ic_help)
                    binding.statusIcon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.orange_dark))
                }

                binding.experimentalChip.visibility = if (wpsPin.isExperimental) View.VISIBLE else View.GONE

                binding.copyPinButton.setOnClickListener {
                    copyToClipboard(itemView.context, "WPS PIN", wpsPin.pin)
                }
            }
        }

        private fun showActionsMenu(view: View, result: NetworkDatabaseResult) {
            val popupMenu = PopupMenu(view.context, view)
            popupMenu.menuInflater.inflate(R.menu.credential_actions, popupMenu.menu)

            val wifiKey = result.databaseInfo["WiFiKey"] as? String
                ?: result.databaseInfo["key"] as? String ?: ""

            val wpsPin = result.databaseInfo["WPSPIN"]?.toString()
                ?: result.databaseInfo["wps"]?.toString() ?: ""

            val prefs = view.context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val isRootEnabled = prefs.getBoolean("enable_root", false)
            popupMenu.menu.findItem(R.id.action_connect_wps_root).isVisible = isRootEnabled

            popupMenu.menu.findItem(R.id.action_copy_wifi_key).isEnabled = wifiKey.isNotEmpty()
            popupMenu.menu.findItem(R.id.action_copy_wps_pin).isEnabled = wpsPin.isNotEmpty()

            val hasValidCredentials = QrNavigationHelper.hasValidCredentials(wifiKey, wpsPin)
            popupMenu.menu.findItem(R.id.action_generate_qr)?.isVisible = hasValidCredentials

            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_copy_wifi_key -> copyToClipboard(view.context, "WiFi Key", wifiKey)
                    R.id.action_copy_wps_pin -> copyToClipboard(view.context, "WPS PIN", wpsPin)
                    R.id.action_generate_qr -> {
                        showQrCode(view.context, result)
                    }
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

        private const val TYPE_DATABASE = 0
        private const val TYPE_WPA = 1
        private const val TYPE_WPS = 2

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