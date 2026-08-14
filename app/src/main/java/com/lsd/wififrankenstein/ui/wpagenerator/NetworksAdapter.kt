package com.lsd.wififrankenstein.ui.wpagenerator

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemNetworkBinding
import com.lsd.wififrankenstein.databinding.ItemResultBinding
import com.lsd.wififrankenstein.databinding.ItemResultHeaderBinding

class NetworksAdapter(
    private val onNetworkClick: (WpaGeneratorFragment.NetworkInfo) -> Unit,
    private val onKeyClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_NETWORK = 0
        private const val TYPE_RESULT_HEADER = 1
        private const val TYPE_RESULT_KEY = 2
    }

    private val expandedNetworks = mutableSetOf<String>()
    private val expandedAlgorithms = mutableSetOf<String>()
    private val networkResults = mutableMapOf<String, List<WpaResult>>()
    private val currentNetworks = mutableListOf<WpaGeneratorFragment.NetworkInfo>()
    private val sortedNetworks = mutableListOf<WpaGeneratorFragment.NetworkInfo>()
    private var items = mutableListOf<NetworkItem>()

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is NetworkItem.Network -> TYPE_NETWORK
            is NetworkItem.ResultHeader -> TYPE_RESULT_HEADER
            is NetworkItem.ResultKey -> TYPE_RESULT_KEY
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_NETWORK -> {
                val binding =
                    ItemNetworkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                NetworkViewHolder(binding)
            }

            TYPE_RESULT_HEADER -> {
                val binding = ItemResultHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
                HeaderViewHolder(binding)
            }

            TYPE_RESULT_KEY -> {
                val binding =
                    ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                KeyViewHolder(binding)
            }

            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is NetworkViewHolder -> holder.bind(items[position] as NetworkItem.Network)
            is HeaderViewHolder -> holder.bind(items[position] as NetworkItem.ResultHeader)
            is KeyViewHolder -> holder.bind(items[position] as NetworkItem.ResultKey)
        }
    }

    fun updateNetworks(networks: List<WpaGeneratorFragment.NetworkInfo>) {
        currentNetworks.clear()
        currentNetworks.addAll(networks)
        sortedNetworks.clear()
        sortedNetworks.addAll(networks.sortedByDescending { it.supportState })
        rebuildList()
    }

    fun updateNetworkResults(networkBssid: String, results: List<WpaResult>) {
        networkResults[networkBssid] = results
        expandedNetworks.add(networkBssid)
        rebuildList()
    }

    private fun rebuildList() {
        items.clear()

        for (network in sortedNetworks) {
            items.add(NetworkItem.Network(network))

            val results = networkResults[network.bssid]
            if (results != null && expandedNetworks.contains(network.bssid)) {
                for (result in results) {
                    val algorithmKey = "${network.bssid}_${result.algorithm}"
                    items.add(NetworkItem.ResultHeader(result, network.bssid))

                    if (expandedAlgorithms.contains(algorithmKey)) {
                        for (key in result.keys) {
                            items.add(NetworkItem.ResultKey(key, network.bssid, result.algorithm))
                        }
                    }
                }
            }
        }

        notifyDataSetChanged()
    }

    private fun getAlgorithmKey(networkBssid: String, algorithm: String): String {
        return "${networkBssid}_${algorithm}"
    }

    inner class NetworkViewHolder(private val binding: ItemNetworkBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(networkItem: NetworkItem.Network) {
            val network = networkItem.network
            binding.ssidText.text = network.ssid
            binding.bssidText.text = network.bssid

            val (icon, tint) = when (network.supportState) {
                2 -> Pair(R.drawable.ic_check_circle, R.color.green_dark)
                1 -> Pair(R.drawable.ic_warning, R.color.orange_dark)
                else -> Pair(R.drawable.ic_cancel, R.color.red_dark)
            }

            binding.supportIcon.setImageResource(icon)
            binding.supportIcon.setColorFilter(
                ContextCompat.getColor(binding.root.context, tint),
                PorterDuff.Mode.SRC_IN
            )

            binding.root.setOnClickListener {
                if (expandedNetworks.contains(network.bssid)) {
                    expandedNetworks.remove(network.bssid)
                    rebuildList()
                } else {
                    onNetworkClick(network)
                }
            }
        }
    }

    inner class HeaderViewHolder(private val binding: ItemResultHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(headerItem: NetworkItem.ResultHeader) {
            val result = headerItem.result
            val algorithmKey = getAlgorithmKey(headerItem.networkBssid, result.algorithm)
            val isExpanded = expandedAlgorithms.contains(algorithmKey)

            binding.algorithmText.text =
                binding.root.context.getString(R.string.algorithm_used, result.algorithm)
            binding.keysCountText.text =
                binding.root.context.getString(R.string.wpa_keys_count, result.keys.size)

            val supportText = when (result.supportState) {
                WpaResult.SUPPORTED -> binding.root.context.getString(R.string.support_state_supported)
                WpaResult.UNLIKELY_SUPPORTED -> binding.root.context.getString(R.string.support_state_unlikely)
                else -> binding.root.context.getString(R.string.support_state_unsupported)
            }
            binding.supportStateText.text = supportText

            val supportColor = when (result.supportState) {
                WpaResult.SUPPORTED -> ContextCompat.getColor(
                    binding.root.context,
                    android.R.color.holo_green_dark
                )

                WpaResult.UNLIKELY_SUPPORTED -> ContextCompat.getColor(
                    binding.root.context,
                    android.R.color.holo_orange_dark
                )

                else -> ContextCompat.getColor(binding.root.context, android.R.color.holo_red_dark)
            }
            binding.supportStateText.setTextColor(supportColor)

            binding.expandButton.visibility = View.VISIBLE
            binding.expandButton.setImageResource(
                if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )

            val clickListener = View.OnClickListener {
                if (isExpanded) {
                    expandedAlgorithms.remove(algorithmKey)
                } else {
                    expandedAlgorithms.add(algorithmKey)
                }
                rebuildList()
            }

            binding.root.setOnClickListener(clickListener)
            binding.expandButton.setOnClickListener(clickListener)
        }
    }

    inner class KeyViewHolder(private val binding: ItemResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(keyItem: NetworkItem.ResultKey) {
            binding.keyText.text = keyItem.key
            binding.keyText.setOnClickListener {
                onKeyClick(keyItem.key)
            }

            binding.copyButton.setOnClickListener {
                onKeyClick(keyItem.key)
            }
        }
    }

    sealed class NetworkItem {
        data class Network(val network: WpaGeneratorFragment.NetworkInfo) : NetworkItem()
        data class ResultHeader(val result: WpaResult, val networkBssid: String) : NetworkItem()
        data class ResultKey(val key: String, val networkBssid: String, val algorithm: String) :
            NetworkItem()
    }
}
