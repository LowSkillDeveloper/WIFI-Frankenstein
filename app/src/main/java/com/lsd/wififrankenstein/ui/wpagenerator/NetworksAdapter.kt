package com.lsd.wififrankenstein.ui.wpagenerator

import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.databinding.ItemNetworkBinding
import com.lsd.wififrankenstein.R

class NetworksAdapter(
    private val onNetworkClick: (WpaGeneratorFragment.NetworkInfo) -> Unit
) : ListAdapter<WpaGeneratorFragment.NetworkInfo, NetworksAdapter.NetworkViewHolder>(NetworkDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val binding = ItemNetworkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NetworkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun updateNetworks(networks: List<WpaGeneratorFragment.NetworkInfo>) {
        submitList(networks.sortedByDescending { it.supportState })
    }

    inner class NetworkViewHolder(private val binding: ItemNetworkBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(network: WpaGeneratorFragment.NetworkInfo) {
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
                onNetworkClick(network)
            }
        }
    }

    private class NetworkDiffCallback : DiffUtil.ItemCallback<WpaGeneratorFragment.NetworkInfo>() {
        override fun areItemsTheSame(
            oldItem: WpaGeneratorFragment.NetworkInfo,
            newItem: WpaGeneratorFragment.NetworkInfo
        ): Boolean = oldItem.bssid == newItem.bssid

        override fun areContentsTheSame(
            oldItem: WpaGeneratorFragment.NetworkInfo,
            newItem: WpaGeneratorFragment.NetworkInfo
        ): Boolean = oldItem == newItem
    }
}