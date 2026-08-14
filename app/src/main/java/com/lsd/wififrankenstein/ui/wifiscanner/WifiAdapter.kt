package com.lsd.wififrankenstein.ui.wifiscanner

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.wifi.ScanResult
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
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
import com.lsd.wififrankenstein.util.DbFieldFormatter
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.NetworkDetailsExtractor
import com.lsd.wififrankenstein.util.NetworkProtocol
import com.lsd.wififrankenstein.util.calculateDistanceString
import java.util.Locale

class WifiAdapter(
    private var wifiList: List<ScanResult>,
    private val context: Context,
    private val settings: android.content.SharedPreferences? = null
) :
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
        try {
            val isDarkTheme = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val themeResId = if (isDarkTheme) {
                R.style.Theme_WIFIFrankenstein_Green_Night
            } else {
                R.style.Theme_WIFIFrankenstein_Green
            }
            val themedContext = ContextThemeWrapper(context, themeResId)
            val inflater = LayoutInflater.from(themedContext)
            val binding = ItemWifiBinding.inflate(inflater, parent, false)
            return WifiViewHolder(binding)
        } catch (e: Exception) {
            Log.e("WifiAdapter", "Failed to inflate item_wifi layout", e)
            var cause: Throwable? = e
            while (cause != null) {
                Log.e("WifiAdapter", "Caused by: ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
            }
            throw e
        }
    }

    override fun onBindViewHolder(holder: WifiViewHolder, position: Int) {
        holder.bind(wifiList[position])
    }

    override fun getItemCount() = wifiList.size

    fun updateData(newWifiList: List<ScanResult>) {
        if (databaseResults.isEmpty()) {
            isDatabaseResultsApplied = false
            networksWithDatabaseData.clear()
        }
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

        val shouldPrioritize = settings?.getBoolean("prioritize_networks_with_data", true) ?: true

        if (!shouldPrioritize || !isDatabaseResultsApplied || networksWithDatabaseData.isEmpty()) {
            return wifiList.sortedByDescending { it.level }
        }

        val networksWithData = mutableListOf<ScanResult>()
        val networksWithoutData = mutableListOf<ScanResult>()

        wifiList.forEach { network ->
            val bssid = network.BSSID?.lowercase(Locale.ROOT) ?: ""
            if (networksWithDatabaseData.contains(bssid)) {
                networksWithData.add(network)
            } else {
                networksWithoutData.add(network)
            }
        }

        val sortedNetworksWithData = networksWithData.sortedByDescending { it.level }
        val sortedNetworksWithoutData = networksWithoutData.sortedByDescending { it.level }

        Log.d(
            "WifiAdapter",
            "Networks with data: ${sortedNetworksWithData.size}, without data: ${sortedNetworksWithoutData.size}"
        )
        Log.d(
            "WifiAdapter",
            "Networks with DB data BSSIDs: ${networksWithDatabaseData.joinToString()}"
        )
        sortedNetworksWithData.forEach { network ->
            Log.d(
                "WifiAdapter",
                "With data: ${network.SSID} (${network.BSSID}) - ${network.level} dBm"
            )
        }

        return sortedNetworksWithData + sortedNetworksWithoutData
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

        val sortedList = sortWifiList(wifiList)

        val shouldPrioritize = settings?.getBoolean("prioritize_networks_with_data", true) ?: true
        val shouldAutoScroll =
            settings?.getBoolean("auto_scroll_to_networks_with_data", true) ?: true

        val diffCallback = WifiDiffCallback(wifiList, sortedList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        wifiList = sortedList
        diffResult.dispatchUpdatesTo(this)

        wifiList.forEachIndexed { index, scanResult ->
            if ((scanResult.BSSID?.lowercase(Locale.ROOT) ?: "") in networksWithDatabaseData) {
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

    fun getWifiList() = wifiList

    inner class WifiViewHolder(private val binding: ItemWifiBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val credentialsAdapter = CredentialsAdapter()
        private var isExpanded = false
        private var showAllCredentials = false
        private var fullResultsList: List<NetworkDatabaseResult> = emptyList()
        private var pulseAnimator: ValueAnimator? = null

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
                val ssidRaw = scanResult.SSID
                val isHidden = ssidRaw.isNullOrBlank() || ssidRaw == "<unknown ssid>"
                if (isHidden && !ssidRaw.isNullOrBlank()) {
                    Log.d(
                        "WifiAdapter",
                        "Marked as hidden: BSSID=${scanResult.BSSID}, SSID='$ssidRaw' (len=${ssidRaw.length})"
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
                bssidTextView.text = scanResult.BSSID
                levelTextView.text =
                    itemView.context.getString(R.string.ws_signal_dbm, scanResult.level)

                val distance = calculateDistanceString(scanResult.frequency, scanResult.level, 1.0)
                distanceTextView.text = distance

                securityIcon.setImageResource(networkDetails.security.mainProtocol.iconRes)

                val capabilities = networkDetails.advancedCapabilities
                untrustedChip.visibility = if (capabilities.isUntrusted) View.VISIBLE else View.GONE

                channelInfo.text =
                    itemView.context.getString(R.string.channel_format, networkDetails.channel)
                frequencyInfo.text =
                    itemView.context.getString(networkDetails.frequencyBand.displayNameRes)
                bandwidthInfo.text =
                    itemView.context.getString(networkDetails.bandwidth.displayNameRes)

                if (networkDetails.protocol != NetworkProtocol.UNKNOWN) {
                    protocolInfo.visibility = View.VISIBLE
                    protocolInfo.text =
                        itemView.context.getString(networkDetails.protocol.shortNameRes)
                    protocolFullInfo.visibility = View.VISIBLE
                    protocolFullInfo.text =
                        itemView.context.getString(networkDetails.protocol.fullNameRes)
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

                val securityTypesText =
                    networkDetails.security.getSecurityTypesString(itemView.context)
                if (securityTypesText.isNotBlank() && securityTypesText != itemView.context.getString(
                        R.string.security_type_unknown
                    )
                ) {
                    securityTypesInfo.visibility = View.VISIBLE
                    securityTypesInfo.text = securityTypesText
                } else {
                    securityTypesInfo.visibility = View.GONE
                }

                if (networkDetails.security.hasWps) {
                    wpsInfo.visibility = View.VISIBLE
                    wpsInfo.text = itemView.context.getString(R.string.ws_badge_wps)
                    wpsInfo.setTextColor(ContextCompat.getColor(itemView.context, R.color.blue_500))
                    wpsIcon.visibility = View.VISIBLE
                } else {
                    wpsInfo.visibility = View.GONE
                    wpsIcon.visibility = View.GONE
                }

                if (networkDetails.security.isAdHoc) {
                    adhocInfo.visibility = View.VISIBLE
                    adhocInfo.text = itemView.context.getString(R.string.ws_badge_adhoc)
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

                updateSignalIndicator(scanResult.level)

                val card = itemView as? MaterialCardView
                val networkResults = databaseResults[scanResult.BSSID?.lowercase(Locale.ROOT) ?: ""]
                val hasData = !networkResults.isNullOrEmpty()

                pulseAnimator?.cancel()
                pulseAnimator = null
                card?.animate()?.cancel()
                card?.alpha = 1f

                if (hasData) {
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
                    updateCardSignalTint(scanResult.level)
                }

                if (hasData) {
                    fullResultsList = networkResults
                    expandButton.visibility = View.VISIBLE
                    expandButton.text = itemView.context.getString(R.string.show_database_info)

                    val dbCount = networkResults.map { it.databaseName }.distinct().size
                    recordsCountTextView.visibility = View.VISIBLE
                    recordsCountTextView.text = itemView.context.getString(
                        R.string.records_found,
                        networkResults.size,
                        dbCount
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

        private fun updateExpandButtonIcon() {
            val iconRes = if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            binding.expandButton.icon = ContextCompat.getDrawable(itemView.context, iconRes)
        }

        private fun updateCredentialsVisibility() {
            Log.d(
                "WifiAdapter",
                "Total credentials: ${fullResultsList.size}, isExpanded: $isExpanded, showAllCredentials: $showAllCredentials"
            )
            if (!isExpanded) {
                binding.credentialsRecyclerView.visibility = View.GONE
                binding.expandAllButton.visibility = View.GONE
            } else {
                val results = if (showAllCredentials) fullResultsList else fullResultsList.take(2)
                binding.credentialsRecyclerView.visibility = View.VISIBLE
                binding.credentialsRecyclerView.requestLayout()
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
            return when (viewType) {
                TYPE_DATABASE -> {
                    val binding = ItemCredentialBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                    CredentialsViewHolder(binding)
                }

                TYPE_WPA -> {
                    val binding = ItemWpaResultBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                    WpaViewHolder(binding)
                }

                TYPE_WPS -> {
                    val binding = ItemWpsResultBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                    WpsViewHolder(binding)
                }

                TYPE_WPASEC -> {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_wpasec_result, parent, false)
                    WpaSecViewHolder(view)
                }

                else -> throw IllegalArgumentException("Unknown view type: $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is CredentialsViewHolder -> holder.bind(getItem(position))
                is WpaViewHolder -> holder.bind(getItem(position))
                is WpsViewHolder -> holder.bind(getItem(position))
                is WpaSecViewHolder -> holder.bind(getItem(position))
            }
        }

        inner class CredentialsViewHolder(private val binding: ItemCredentialBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(result: NetworkDatabaseResult) {
                Log.d("CredentialsAdapter", "Binding credentials for ${result.network.SSID}")
                Log.d("CredentialsAdapter", "Database info: ${result.databaseInfo}")

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

        inner class WpaViewHolder(private val binding: ItemWpaResultBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(result: NetworkDatabaseResult) {
                val wpaResult = result.wpaResult ?: return

                binding.algorithmName.text = wpaResult.algorithm
                binding.wpaKeysText.text = wpaResult.keys.joinToString("\n")
                binding.generationTime.text =
                    itemView.context.getString(R.string.generation_time, wpaResult.generationTime)

                binding.copyKeysButton.setOnClickListener {
                    DatabaseResultActionsHandler.copyToClipboard(
                        itemView.context,
                        "WPA Key",
                        wpaResult.keys.joinToString("\n")
                    )
                }
            }
        }

        inner class WpsViewHolder(private val binding: ItemWpsResultBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(result: NetworkDatabaseResult) {
                val wpsPin = result.wpsPin ?: return

                binding.algorithmName.text = wpsPin.name
                binding.wpsPinText.text = wpsPin.pin

                val source = wpsPin.additionalData["source"] as? String
                val distance = wpsPin.additionalData["distance"] as? String

                val sourceText = when {
                    source == "neighbor_search" && distance != null ->
                        itemView.context.getString(
                            R.string.source_format,
                            "${wpsPin.name} (${distance} MAC distance)"
                        )

                    source != null ->
                        itemView.context.getString(R.string.source_format, source)

                    else -> ""
                }
                binding.sourceInfo.text = sourceText

                binding.scoreText.text =
                    itemView.context.getString(R.string.score_format, wpsPin.score)

                if (wpsPin.sugg) {
                    binding.statusIcon.setImageResource(R.drawable.ic_star)
                    binding.statusIcon.setColorFilter(
                        ContextCompat.getColor(
                            itemView.context,
                            R.color.orange_dark
                        )
                    )
                } else {
                    binding.statusIcon.setImageResource(R.drawable.ic_help)
                    binding.statusIcon.setColorFilter(
                        ContextCompat.getColor(
                            itemView.context,
                            R.color.orange_dark
                        )
                    )
                }

                binding.experimentalChip.visibility =
                    if (wpsPin.isExperimental) View.VISIBLE else View.GONE

                binding.copyPinButton.setOnClickListener {
                    DatabaseResultActionsHandler.copyToClipboard(
                        itemView.context,
                        "WPS PIN",
                        wpsPin.pin
                    )
                }
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

    class WifiDiffCallback(
        private val oldList: List<ScanResult>,
        private val newList: List<ScanResult>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            (oldList[oldItemPosition].BSSID?.lowercase(Locale.ROOT) ?: "") ==
                    (newList[newItemPosition].BSSID?.lowercase(Locale.ROOT) ?: "")

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition] == newList[newItemPosition]
    }

    companion object {
        private const val TYPE_DATABASE = 0
        private const val TYPE_WPA = 1
        private const val TYPE_WPS = 2
        private const val TYPE_WPASEC = 3
    }

    class CredentialsDiffCallback : DiffUtil.ItemCallback<NetworkDatabaseResult>() {
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
}
