package com.lsd.wififrankenstein.ui.wifianalysis

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemNetworkInChannelBinding

sealed class NetworkListItem(open val network: NetworkChannelInfo) {
    data class OnChannel(override val network: NetworkChannelInfo) : NetworkListItem(network)
    data class Interfering(override val network: NetworkChannelInfo) : NetworkListItem(network)
}

class NetworkInChannelAdapter :
    ListAdapter<NetworkListItem, NetworkInChannelAdapter.ViewHolder>(DiffCallback()) {

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

    class ViewHolder(private val binding: ItemNetworkInChannelBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(networkItem: NetworkListItem) {
            val networkInfo = networkItem.network
            val isInterfering = networkItem is NetworkListItem.Interfering

            binding.apply {
                textViewNetworkName.text = if (networkInfo.scanResult.SSID.isBlank()) {
                    itemView.context.getString(R.string.no_ssid)
                } else {
                    networkInfo.scanResult.SSID
                }

                textViewNetworkBssid.text = networkInfo.scanResult.BSSID

                textViewChannelWidth.text = itemView.context.getString(
                    R.string.channel_width_format,
                    networkInfo.channelWidth.widthMHz
                )

                val subChannelInfo = generateSubChannelInfo(networkInfo)
                textViewSubChannel.text = subChannelInfo

                val signalText =
                    itemView.context.getString(R.string.wa_signal_dbm, networkInfo.scanResult.level)
                textViewSignalLevel.text = signalText

                val signalColor = when {
                    networkInfo.scanResult.level >= -50 -> ContextCompat.getColor(
                        itemView.context,
                        R.color.green_500
                    )

                    networkInfo.scanResult.level >= -70 -> ContextCompat.getColor(
                        itemView.context,
                        R.color.orange_500
                    )

                    else -> ContextCompat.getColor(itemView.context, R.color.red_500)
                }
                textViewSignalLevel.setTextColor(signalColor)

                if (isInterfering) {
                    root.setBackgroundColor(
                        ContextCompat.getColor(
                            itemView.context,
                            R.color.gray_200
                        )
                    )
                    textViewSubChannel.text =
                        itemView.context.getString(R.string.wa_interferes, networkInfo.channel)
                } else {
                    root.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.white))
                }
            }
        }

        private fun generateSubChannelInfo(networkInfo: NetworkChannelInfo): String {
            val bandwidth = networkInfo.channelWidth
            val channel = networkInfo.channel
            val context = itemView.context

            return when (networkInfo.band) {
                FrequencyBand.GHZ_2_4 -> {
                    when (bandwidth) {
                        ChannelBandwidth.WIDTH_40 -> {
                            val isUpper = channel in 5..11
                            if (isUpper) {
                                context.getString(R.string.wa_channel_upper, channel)
                            } else {
                                context.getString(R.string.wa_channel_lower, channel)
                            }
                        }

                        else -> context.getString(R.string.wa_channel, channel)
                    }
                }

                FrequencyBand.GHZ_5, FrequencyBand.GHZ_6 -> {
                    when (bandwidth) {
                        ChannelBandwidth.WIDTH_40 -> context.getString(R.string.wa_channel_plus, channel)
                        ChannelBandwidth.WIDTH_80 -> context.getString(R.string.wa_channel_plus3, channel)
                        ChannelBandwidth.WIDTH_80_PLUS_80 -> context.getString(
                            R.string.wa_channel_plus3_80,
                            channel
                        )

                        ChannelBandwidth.WIDTH_160 -> context.getString(R.string.wa_channel_plus7, channel)
                        ChannelBandwidth.WIDTH_320 -> context.getString(R.string.wa_channel_plus15, channel)
                        else -> context.getString(R.string.wa_channel, channel)
                    }
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<NetworkListItem>() {
        override fun areItemsTheSame(oldItem: NetworkListItem, newItem: NetworkListItem): Boolean {
            return oldItem.network.scanResult.BSSID == newItem.network.scanResult.BSSID
        }

        override fun areContentsTheSame(
            oldItem: NetworkListItem,
            newItem: NetworkListItem
        ): Boolean {
            return oldItem == newItem
        }
    }
}
