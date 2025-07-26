package com.lsd.wififrankenstein.ui.wifianalysis

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemNetworkInChannelBinding

class NetworkInChannelAdapter : ListAdapter<NetworkChannelInfo, NetworkInChannelAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNetworkInChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemNetworkInChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(networkInfo: NetworkChannelInfo) {
            binding.apply {
                textViewNetworkName.text = if (networkInfo.scanResult.SSID.isBlank()) {
                    itemView.context.getString(R.string.no_ssid)
                } else {
                    networkInfo.scanResult.SSID
                }

                textViewNetworkBssid.text = networkInfo.scanResult.BSSID

                textViewChannelWidth.text = itemView.context.getString(R.string.channel_width_format, networkInfo.channelWidth.widthMHz)

                val subChannelInfo = generateSubChannelInfo(networkInfo)
                textViewSubChannel.text = subChannelInfo

                val signalText = "${networkInfo.scanResult.level} dBm"
                textViewSignalLevel.text = signalText

                val signalColor = when {
                    networkInfo.scanResult.level >= -50 -> ContextCompat.getColor(itemView.context, R.color.green_500)
                    networkInfo.scanResult.level >= -70 -> ContextCompat.getColor(itemView.context, R.color.orange_500)
                    else -> ContextCompat.getColor(itemView.context, R.color.red_500)
                }
                textViewSignalLevel.setTextColor(signalColor)
            }
        }

        private fun generateSubChannelInfo(networkInfo: NetworkChannelInfo): String {
            val bandwidth = networkInfo.channelWidth
            val channel = networkInfo.channel

            return when (networkInfo.band) {
                FrequencyBand.GHZ_2_4 -> {
                    when (bandwidth) {
                        ChannelBandwidth.WIDTH_40 -> {
                            val isUpper = channel in 5..11
                            if (isUpper) "Ch $channel+" else "Ch $channel-"
                        }
                        else -> "Ch $channel"
                    }
                }
                FrequencyBand.GHZ_5, FrequencyBand.GHZ_6 -> {
                    when (bandwidth) {
                        ChannelBandwidth.WIDTH_40 -> "Ch $channel+1"
                        ChannelBandwidth.WIDTH_80 -> "Ch $channel+3"
                        ChannelBandwidth.WIDTH_160 -> "Ch $channel+7"
                        ChannelBandwidth.WIDTH_320 -> "Ch $channel+15"
                        else -> "Ch $channel"
                    }
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<NetworkChannelInfo>() {
        override fun areItemsTheSame(oldItem: NetworkChannelInfo, newItem: NetworkChannelInfo): Boolean {
            return oldItem.scanResult.BSSID == newItem.scanResult.BSSID
        }

        override fun areContentsTheSame(oldItem: NetworkChannelInfo, newItem: NetworkChannelInfo): Boolean {
            return oldItem.scanResult.BSSID == newItem.scanResult.BSSID &&
                    oldItem.scanResult.SSID == newItem.scanResult.SSID &&
                    oldItem.scanResult.level == newItem.scanResult.level &&
                    oldItem.scanResult.frequency == newItem.scanResult.frequency &&
                    oldItem.channelWidth == newItem.channelWidth
        }
    }
}