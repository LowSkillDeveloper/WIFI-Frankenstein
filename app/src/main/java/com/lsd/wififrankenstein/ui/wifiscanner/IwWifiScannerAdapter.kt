package com.lsd.wififrankenstein.ui.wifiscanner

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemCredentialBinding
import com.lsd.wififrankenstein.databinding.ItemWifiBinding
import com.lsd.wififrankenstein.databinding.ItemWpaResultBinding
import com.lsd.wififrankenstein.databinding.ItemWpsResultBinding
import com.lsd.wififrankenstein.ui.iwwifi.PixieDustChecker
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.util.DbFieldFormatter
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.calculateDistanceString
import java.util.Locale

class IwWifiScannerAdapter(
    private var networkList: List<IwWifiNetwork>,
    private val context: Context,
    private val settings: android.content.SharedPreferences? = null
) : RecyclerView.Adapter<IwWifiScannerAdapter.NetworkViewHolder>() {

    private var onItemClickListener: ((View, IwWifiNetwork) -> Unit)? = null
    private var databaseResults: Map<String, List<NetworkDatabaseResult>> = emptyMap()
    private var onScrollToTopListener: (() -> Unit)? = null

    private var isDatabaseResultsApplied = false
    private var networksWithDatabaseData = mutableSetOf<String>()

    fun setOnScrollToTopListener(listener: () -> Unit) {
        this.onScrollToTopListener = listener
    }

    fun setOnItemClickListener(listener: (View, IwWifiNetwork) -> Unit) {
        this.onItemClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val binding = ItemWifiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NetworkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        holder.bind(networkList[position])
    }

    override fun getItemCount() = networkList.size

    fun updateData(newList: List<IwWifiNetwork>) {
        if (databaseResults.isEmpty()) {
            isDatabaseResultsApplied = false
            networksWithDatabaseData.clear()
        }
        val sorted = sortNetworks(newList)
        val diffCallback = IwNetworkDiffCallback(networkList, sorted)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        networkList = sorted
        diffResult.dispatchUpdatesTo(this)
    }

    private fun sortNetworks(list: List<IwWifiNetwork>): List<IwWifiNetwork> {
        if (list.isEmpty()) return list

        val shouldPrioritize = settings?.getBoolean("prioritize_networks_with_data", true) ?: true

        if (!shouldPrioritize || !isDatabaseResultsApplied || networksWithDatabaseData.isEmpty()) {
            return list.sortedByDescending { it.signalStrength }
        }

        val withData = mutableListOf<IwWifiNetwork>()
        val withoutData = mutableListOf<IwWifiNetwork>()

        list.forEach { network ->
            val bssid = network.bssid.lowercase(Locale.ROOT)
            if (networksWithDatabaseData.contains(bssid)) {
                withData.add(network)
            } else {
                withoutData.add(network)
            }
        }

        return withData.sortedByDescending { it.signalStrength } +
                withoutData.sortedByDescending { it.signalStrength }
    }

    fun mergeDatabaseResults(newResults: Map<String, List<NetworkDatabaseResult>>) {
        val merged = databaseResults.toMutableMap()

        newResults.forEach { (newBssid, newNetworkResults) ->
            val existingResults = merged[newBssid]?.toMutableList() ?: mutableListOf()

            newNetworkResults.forEach { newItem ->
                val isDuplicate = existingResults.any { existingItem ->
                    when (newItem.resultType) {
                        ResultType.WPS_ALGORITHM -> {
                            existingItem.resultType == ResultType.WPS_ALGORITHM &&
                                    newItem.wpsPin?.pin == existingItem.wpsPin?.pin &&
                                    newItem.databaseName == existingItem.databaseName
                        }

                        ResultType.WPA_ALGORITHM -> {
                            existingItem.resultType == ResultType.WPA_ALGORITHM &&
                                    newItem.wpaResult?.algorithm == existingItem.wpaResult?.algorithm &&
                                    newItem.wpaResult?.keys == existingItem.wpaResult?.keys
                        }

                        ResultType.DATABASE -> {
                            existingItem.resultType == ResultType.DATABASE &&
                                    newItem.databaseName == existingItem.databaseName &&
                                    newItem.databaseInfo == existingItem.databaseInfo
                        }

                        else -> false
                    }
                }

                if (!isDuplicate) {
                    existingResults.add(newItem)
                }
            }

            merged[newBssid] = sortCredentialsWithWpaSecFirst(existingResults)
        }

        databaseResults = merged.mapKeys { (key, _) -> key.lowercase(Locale.ROOT) }

        networksWithDatabaseData.clear()
        databaseResults.forEach { (bssid, networkResults) ->
            if (networkResults.isNotEmpty()) {
                networksWithDatabaseData.add(bssid.lowercase(Locale.ROOT))
            }
        }

        val hasNetworksWithData = networksWithDatabaseData.isNotEmpty()
        if (hasNetworksWithData && !isDatabaseResultsApplied) {
            isDatabaseResultsApplied = true
        }

        val sortedList = sortNetworks(networkList)

        val shouldPrioritize = settings?.getBoolean("prioritize_networks_with_data", true) ?: true
        val shouldAutoScroll =
            settings?.getBoolean("auto_scroll_to_networks_with_data", true) ?: true

        val diffCallback = IwNetworkDiffCallback(networkList, sortedList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        networkList = sortedList
        diffResult.dispatchUpdatesTo(this)

        networkList.forEachIndexed { index, network ->
            if (network.bssid.lowercase(Locale.ROOT) in networksWithDatabaseData) {
                notifyItemChanged(index)
            }
        }

        if (shouldPrioritize && hasNetworksWithData && shouldAutoScroll) {
            onScrollToTopListener?.invoke()
        }
    }

    private fun sortCredentialsWithWpaSecFirst(
        results: List<NetworkDatabaseResult>
    ): List<NetworkDatabaseResult> {
        return results.sortedBy { result ->
            if (result.databaseInfo["isWpaSec"] == true) 0 else 1
        }
    }

    fun clearDatabaseResults() {
        databaseResults = emptyMap()
        isDatabaseResultsApplied = false
        networksWithDatabaseData.clear()
        notifyDataSetChanged()
    }

    fun getNetworkList() = networkList

    inner class NetworkViewHolder(private val binding: ItemWifiBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val credentialsAdapter = CredentialsAdapter()
        private var isExpanded = false
        private var showAllCredentials = false
        private var fullResultsList: List<NetworkDatabaseResult> = emptyList()
        private var pulseAnimator: ValueAnimator? = null

        init {
            itemView.setOnClickListener { view ->
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClickListener?.invoke(view, networkList[position])
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
                Log.d("IwWifiScannerAdapter", "Expand all button clicked")
            }

            binding.credentialsRecyclerView.layoutManager = LinearLayoutManager(itemView.context)
            binding.credentialsRecyclerView.adapter = credentialsAdapter
        }

        fun bind(network: IwWifiNetwork) {
            val freqInt = network.frequency.toIntOrNull() ?: 2412
            val signalInt = network.signalStrength
            val distance = calculateDistanceString(freqInt, signalInt, 1.0)
            val security = parseSecurity(network.capabilities)

            binding.apply {
                val ssidRaw = network.ssid
                val isHidden = ssidRaw.isNullOrBlank() || ssidRaw == "<unknown ssid>"
                if (isHidden && !ssidRaw.isNullOrBlank()) {
                    com.lsd.wififrankenstein.util.Log.d(
                        "IwWifiScannerAdapter",
                        "Marked as hidden: BSSID=${network.bssid}, SSID='$ssidRaw' (len=${ssidRaw.length})"
                    )
                }
                ssidTextView.text =
                    if (isHidden) itemView.context.getString(R.string.hidden_network) else (ssidRaw
                        ?: "?")
                ssidTextView.setTextColor(
                    ContextCompat.getColor(
                        itemView.context,
                        if (isHidden) R.color.text_hint else R.color.text_primary
                    )
                )


                val modelText = if (network.wpsModel.isNotBlank()) {
                    " (${network.wpsModel})"
                } else ""
                bssidTextView.text = "${network.bssid}$modelText"

                levelTextView.text = "${signalInt} dBm"
                distanceTextView.text = distance

                securityIcon.setImageResource(security.iconRes)

                channelInfo.text = itemView.context.getString(
                    R.string.channel_format,
                    frequencyToChannel(freqInt)
                )
                frequencyInfo.text = itemView.context.getString(
                    frequencyToBand(freqInt).displayNameRes
                )


                val channelWidth = getChannelWidthInfo(network)
                if (channelWidth != null) {
                    bandwidthInfo.visibility = View.VISIBLE
                    bandwidthInfo.text = channelWidth
                } else {
                    bandwidthInfo.visibility = View.GONE
                }

                protocolInfo.visibility = View.GONE
                protocolFullInfo.visibility = View.GONE

                securityInfo.text = security.getSecurityString()
                securityInfo.visibility = View.VISIBLE

                securityTypesInfo.visibility = View.GONE

                untrustedChip.visibility = View.GONE

                if (security.hasWps || network.wpsEnabled) {
                    wpsInfo.visibility = View.VISIBLE
                    wpsInfo.text = "WPS"
                    wpsInfo.setTextColor(ContextCompat.getColor(itemView.context, R.color.blue_500))
                    wpsIcon.visibility = View.VISIBLE
                    val wpsTint = if (PixieDustChecker.isPixieDustVulnerable(network)) {
                        R.color.orange_500
                    } else {
                        R.color.blue_500
                    }
                    wpsIcon.imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(itemView.context, wpsTint)
                    )
                } else {
                    wpsInfo.visibility = View.GONE
                    wpsIcon.visibility = View.GONE
                }


                if (PixieDustChecker.isPixieDustVulnerable(network)) {
                    pixieDustInfo.visibility = View.VISIBLE
                    pixieDustInfo.text =
                        itemView.context.getString(R.string.pixie_dust_vulnerable_short)
                } else {
                    pixieDustInfo.visibility = View.GONE
                }

                adhocInfo.visibility = View.GONE
                fastRoamingInfo.visibility = View.GONE
                rttInfo.visibility = View.GONE
                ntbInfo.visibility = View.GONE
                twtInfo.visibility = View.GONE
                mldInfo.visibility = View.GONE

                updateSignalIndicator(signalInt)

                val card = itemView as? MaterialCardView
                card?.animate()?.cancel()
                card?.alpha = 1f
                val networkResults = databaseResults[network.bssid.lowercase(Locale.ROOT)]
                val hasData = !networkResults.isNullOrEmpty()

                pulseAnimator?.cancel()
                pulseAnimator = null
                card?.animate()?.cancel()
                card?.alpha = 1f

                if (PixieDustChecker.isPixieDustVulnerable(network)) {
                    pixieDustInfo.visibility = View.VISIBLE
                    pixieDustInfo.text =
                        itemView.context.getString(R.string.pixie_dust_vulnerable_short)
                    pixieDustInfo.background =
                        ContextCompat.getDrawable(itemView.context, R.drawable.bg_chip_pixiedust)
                    pixieDustInfo.backgroundTintList = null
                    pixieDustInfo.setTextColor(
                        ContextCompat.getColor(
                            itemView.context,
                            R.color.blue_500
                        )
                    )

                    val blue = ContextCompat.getColor(itemView.context, R.color.blue_500)
                    card?.setStrokeColor(ColorStateList.valueOf(blue))
                    card?.strokeWidth =
                        itemView.resources.getDimensionPixelSize(R.dimen.stroke_pixiedust)
                } else if (hasData) {
                    pixieDustInfo.visibility = View.GONE
                    pixieDustInfo.background = null
                    pixieDustInfo.setTextColor(
                        ContextCompat.getColor(
                            itemView.context,
                            R.color.error_red
                        )
                    )

                    val blue = ContextCompat.getColor(itemView.context, R.color.blue_500)
                    card?.setStrokeColor(ColorStateList.valueOf(blue))
                    card?.strokeWidth =
                        itemView.resources.getDimensionPixelSize(R.dimen.stroke_default)
                    val blueDim = ContextCompat.getColor(itemView.context, R.color.blue_200)
                    pulseAnimator = ValueAnimator.ofObject(ArgbEvaluator(), blue, blueDim).apply {
                        duration = 2000
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener { anim ->
                            card?.setStrokeColor(ColorStateList.valueOf(anim.animatedValue as Int))
                        }
                        start()
                    }
                } else {
                    pixieDustInfo.visibility = View.GONE
                    pixieDustInfo.background = null
                    pixieDustInfo.setTextColor(
                        ContextCompat.getColor(
                            itemView.context,
                            R.color.error_red
                        )
                    )

                    card?.strokeWidth =
                        itemView.resources.getDimensionPixelSize(R.dimen.stroke_default)
                    updateCardSignalTint(signalInt)
                }

                if (hasData) {
                    fullResultsList = networkResults
                    expandButton.visibility = View.VISIBLE
                    expandButton.text = itemView.context.getString(R.string.show_database_info)

                    val dbCount = networkResults.map { it.databaseName }.distinct().size
                    recordsCountTextView.visibility = View.VISIBLE
                    recordsCountTextView.text = itemView.context.getString(
                        R.string.records_found, networkResults.size, dbCount
                    )

                    updateCredentialsVisibility()
                } else {
                    expandButton.visibility = View.GONE
                    recordsCountTextView.visibility = View.GONE
                    credentialsRecyclerView.visibility = View.GONE
                    expandAllButton.visibility = View.GONE
                }
                updateExpandButtonIcon()
            }
        }

        private fun updateCardSignalTint(level: Int) {
            val card = itemView as? MaterialCardView ?: return
            val strokeColor = ContextCompat.getColor(
                itemView.context, when {
                    level >= -60 -> R.color.signal_good
                    level >= -75 -> R.color.signal_fair
                    else -> R.color.signal_poor
                }
            )
            card.setStrokeColor(ColorStateList.valueOf(strokeColor))
        }

        private fun updateSignalIndicator(level: Int) {
            val bars = listOf(
                binding.signalBar1,
                binding.signalBar2,
                binding.signalBar3,
                binding.signalBar4
            )
            val activeCount = when {
                level >= -50 -> 4
                level >= -60 -> 3
                level >= -70 -> 2
                level >= -85 -> 1
                else -> 0
            }
            val activeColor = ContextCompat.getColor(
                itemView.context, when {
                    level >= -60 -> R.color.signal_good
                    level >= -75 -> R.color.signal_fair
                    else -> R.color.signal_poor
                }
            )
            val inactiveColor =
                ContextCompat.getColor(itemView.context, R.color.signal_bar_inactive)
            bars.forEachIndexed { index, bar ->
                val color = if (index < activeCount) activeColor else inactiveColor
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    bar.backgroundTintList = ColorStateList.valueOf(color)
                }
            }
        }

        private fun getChannelWidthInfo(network: IwWifiNetwork): String? {

            if (network.heCapabilities.isNotEmpty()) {
                return when {
                    network.heCapabilities.contains("160 MHz", ignoreCase = true) ||
                            network.heCapabilities.contains(
                                "80+80 MHz",
                                ignoreCase = true
                            ) -> "HT160"

                    network.heCapabilities.contains("80 MHz", ignoreCase = true) -> "HT80"
                    else -> null
                }
            }


            val caps = network.capabilities.uppercase(Locale.ROOT)
            if (caps.contains("VHT")) {
                return when {
                    caps.contains("160MHZ") || caps.contains("80+80MHZ") -> "HT160"
                    caps.contains("80MHZ") -> "HT80"
                    else -> null
                }
            }


            return when {
                network.htHt20Ht40 -> "HT20/40"
                network.htChannelWidth.contains("40") -> "HT40"
                network.htChannelWidth.contains("20") -> "HT20"
                else -> null
            }
        }

        private fun updateExpandButtonIcon() {
            val iconRes = if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            binding.expandButton.icon = ContextCompat.getDrawable(itemView.context, iconRes)
        }

        private fun updateCredentialsVisibility() {
            if (!isExpanded) {
                binding.credentialsRecyclerView.visibility = View.GONE
                binding.expandAllButton.visibility = View.GONE
            } else {
                val results = if (showAllCredentials) fullResultsList else fullResultsList.take(2)
                binding.credentialsRecyclerView.visibility = View.VISIBLE
                if (showAllCredentials || fullResultsList.size <= 2) {
                    binding.expandAllButton.visibility = View.GONE
                } else {
                    binding.expandAllButton.visibility = View.VISIBLE
                }
                credentialsAdapter.submitList(results)
            }
        }
    }

    private inner class CredentialsAdapter :
        ListAdapter<NetworkDatabaseResult, RecyclerView.ViewHolder>(
            CredentialsDiffCallback()
        ) {
        override fun getItemViewType(position: Int): Int {
            val item = getItem(position)
            if (item.databaseInfo["isWpaSec"] == true) return TYPE_WPASEC
            return when (item.resultType) {
                ResultType.DATABASE -> TYPE_DATABASE
                ResultType.WPA_ALGORITHM -> TYPE_WPA
                ResultType.WPS_ALGORITHM -> TYPE_WPS
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_DATABASE -> {
                    val binding = ItemCredentialBinding.inflate(inflater, parent, false)
                    DatabaseViewHolder(binding)
                }

                TYPE_WPA -> {
                    val binding = ItemWpaResultBinding.inflate(inflater, parent, false)
                    WpaResultViewHolder(binding)
                }

                TYPE_WPS -> {
                    val binding = ItemWpsResultBinding.inflate(inflater, parent, false)
                    WpsResultViewHolder(binding)
                }

                TYPE_WPASEC -> {
                    val view = inflater.inflate(R.layout.item_wpasec_result, parent, false)
                    WpaSecViewHolder(view)
                }

                else -> throw IllegalArgumentException("Unknown view type: $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = getItem(position)
            when (holder) {
                is DatabaseViewHolder -> holder.bind(item)
                is WpaResultViewHolder -> holder.bind(item)
                is WpsResultViewHolder -> holder.bind(item)
                is WpaSecViewHolder -> holder.bind(item)
            }
        }

        inner class DatabaseViewHolder(private val binding: ItemCredentialBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(result: NetworkDatabaseResult) {
                binding.essidTextView.text = DatabaseResultActionsHandler.essid(result)
                binding.bssidTextView.text = DatabaseResultActionsHandler.bssid(result)
                binding.databaseNameTextView.text =
                    DatabaseResultActionsHandler.extractDatabaseName(result.databaseName)

                val wifiKey = DatabaseResultActionsHandler.wifiKey(result)
                binding.wifiKeyCopyButton.setOnClickListener {
                    DatabaseResultActionsHandler.copyToClipboard(
                        itemView.context,
                        "WiFi Key",
                        wifiKey.orEmpty()
                    )
                }
                if (!wifiKey.isNullOrBlank()) {
                    binding.wifiKeyContainer.visibility = View.VISIBLE
                    binding.wifiKeyTextView.text =
                        itemView.context.getString(R.string.wifi_key_format, wifiKey)
                    binding.wifiKeyTextView.setTextColor(
                        DatabaseResultActionsHandler.credentialColor(
                            itemView.context,
                            DatabaseResultActionsHandler.isWifiKeyInvalid(wifiKey)
                        )
                    )
                } else {
                    binding.wifiKeyContainer.visibility = View.GONE
                }

                val wpsPin = DatabaseResultActionsHandler.wpsPin(result)
                binding.wpsPinCopyButton.setOnClickListener {
                    DatabaseResultActionsHandler.copyToClipboard(
                        itemView.context,
                        "WPS PIN",
                        wpsPin.orEmpty()
                    )
                }
                if (!wpsPin.isNullOrBlank()) {
                    binding.wpsPinContainer.visibility = View.VISIBLE
                    binding.wpsPinTextView.text =
                        itemView.context.getString(R.string.wps_pin_format, wpsPin)
                    binding.wpsPinTextView.setTextColor(
                        DatabaseResultActionsHandler.credentialColor(
                            itemView.context,
                            DatabaseResultActionsHandler.isWpsPinInvalid(wpsPin)
                        )
                    )
                } else {
                    binding.wpsPinContainer.visibility = View.GONE
                }

                val timeValue = result.databaseInfo["time"]
                val formattedTime = DbFieldFormatter.formatTime(timeValue)
                if (formattedTime != null) {
                    binding.timeContainer.visibility = View.VISIBLE
                    binding.timeTextView.text = formattedTime
                } else {
                    binding.timeContainer.visibility = View.GONE
                }

                val hasKeyOrWps = !wifiKey.isNullOrBlank() || !wpsPin.isNullOrBlank()
                binding.actionsButton.visibility = if (hasKeyOrWps) View.VISIBLE else View.GONE

                val hasCoords = DatabaseResultActionsHandler.hasValidCoordinates(result)
                binding.mapButton.isEnabled = hasCoords
                binding.mapButton.iconTint = ColorStateList.valueOf(
                    if (hasCoords) {
                        val typedValue = TypedValue()
                        itemView.context.theme.resolveAttribute(
                            android.R.attr.colorPrimary,
                            typedValue,
                            true
                        )
                        typedValue.data
                    } else {
                        ContextCompat.getColor(itemView.context, R.color.text_hint)
                    }
                )

                binding.actionsButton.setOnClickListener {
                    DatabaseResultActionsHandler.showActionsMenu(it, result)
                }

                binding.mapButton.setOnClickListener {
                    DatabaseResultActionsHandler.openMap(it, result)
                }

                binding.infoButton.setOnClickListener {
                    DatabaseResultActionsHandler.showAdditionalInfo(it, result)
                }
            }
        }

        inner class WpaResultViewHolder(private val binding: ItemWpaResultBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(result: NetworkDatabaseResult) {
                val wpa = result.wpaResult ?: return
                binding.algorithmName.text = wpa.algorithm
                binding.wpaKeysText.text = wpa.keys.joinToString("\n")
            }
        }

        inner class WpsResultViewHolder(private val binding: ItemWpsResultBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(result: NetworkDatabaseResult) {
                val pin = result.wpsPin ?: return
                binding.algorithmName.text = pin.name
                binding.wpsPinText.text = pin.pin
            }
        }

        inner class WpaSecViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(result: NetworkDatabaseResult) {
                itemView.findViewById<TextView>(R.id.textWpaSecTitle).text =
                    itemView.context.getString(R.string.wpasec_cracked_title)
                itemView.findViewById<TextView>(R.id.textWpaSecDesc).text =
                    itemView.context.getString(R.string.wpasec_cracked_desc)
            }
        }
    }

    private data class SecurityInfo(
        val iconRes: Int,
        val hasWps: Boolean = false
    ) {
        fun getSecurityString(): String {
            return when (iconRes) {
                R.drawable.ic_lock -> "WPA2/WPA3"
                R.drawable.ic_lock_outline -> "WEP"
                R.drawable.ic_lock_open -> "Open"
                else -> "Unknown"
            }
        }
    }

    private fun parseSecurity(capabilities: String): SecurityInfo {
        val caps = capabilities.uppercase(Locale.ROOT)
        val iconRes = when {
            caps.contains("WPA3") || caps.contains("SAE") || caps.contains("RSN") -> R.drawable.ic_lock
            caps.contains("WPA") || caps.contains("PSK") -> R.drawable.ic_lock
            caps.contains("WEP") -> R.drawable.ic_lock_outline
            caps.contains("[ESS]") && !caps.contains("PSK") && !caps.contains("EAP") -> R.drawable.ic_lock_open
            else -> R.drawable.ic_lock
        }

        val hasWps = caps.contains("WPS")

        return SecurityInfo(iconRes = iconRes, hasWps = hasWps)
    }

    private fun frequencyToChannel(freq: Int): Int {
        return when {
            freq == 2484 -> 14
            freq in 2412..2484 -> (freq - 2407) / 5
            freq in 5000..5900 -> (freq - 5000) / 5
            freq in 5925..7125 -> (freq - 5950) / 5
            else -> 0
        }
    }

    private fun frequencyToBand(freq: Int): Band {
        return when {
            freq in 2400..2500 -> Band.BAND_2_4_GHZ
            freq in 5000..5900 -> Band.BAND_5_GHZ
            freq in 5900..7100 -> Band.BAND_6_GHZ
            else -> Band.BAND_UNKNOWN
        }
    }

    enum class Band(val displayNameRes: Int) {
        BAND_2_4_GHZ(R.string.frequency_band_2ghz),
        BAND_5_GHZ(R.string.frequency_band_5ghz),
        BAND_6_GHZ(R.string.frequency_band_6ghz),
        BAND_UNKNOWN(R.string.security_type_unknown)
    }

    private class IwNetworkDiffCallback(
        private val oldList: List<IwWifiNetwork>,
        private val newList: List<IwWifiNetwork>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos].bssid.lowercase(Locale.ROOT) == newList[newPos].bssid.lowercase(
                Locale.ROOT
            )
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos] == newList[newPos]
        }
    }

    private class CredentialsDiffCallback : DiffUtil.ItemCallback<NetworkDatabaseResult>() {
        override fun areItemsTheSame(
            oldItem: NetworkDatabaseResult,
            newItem: NetworkDatabaseResult
        ): Boolean {
            return oldItem.databaseName == newItem.databaseName &&
                    oldItem.resultType == newItem.resultType
        }

        override fun areContentsTheSame(
            oldItem: NetworkDatabaseResult,
            newItem: NetworkDatabaseResult
        ): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        private const val TYPE_DATABASE = 0
        private const val TYPE_WPA = 1
        private const val TYPE_WPS = 2
        private const val TYPE_WPASEC = 3
    }
}
