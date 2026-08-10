package com.lsd.wififrankenstein.ui.pixiedust

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemPixieDustNetworkBinding
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork

class PixieDustAdapter(
    private val onNetworkClick: (IwWifiNetwork) -> Unit
) : RecyclerView.Adapter<PixieDustAdapter.NetworkViewHolder>() {

    private var networks: List<IwWifiNetwork> = emptyList()
    private var selectedPosition: Int = -1

    fun updateNetworks(newNetworks: List<IwWifiNetwork>) {
        val diffCallback = NetworkDiffCallback(networks, newNetworks)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        networks = newNetworks
        diffResult.dispatchUpdatesTo(this)
    }

    fun setSelectedPosition(position: Int) {
        val previous = selectedPosition
        selectedPosition = position
        if (previous >= 0 && previous != selectedPosition) {
            notifyItemChanged(previous)
        }
        if (selectedPosition >= 0) {
            notifyItemChanged(selectedPosition)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val binding = ItemPixieDustNetworkBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NetworkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        holder.bind(networks[position], position == selectedPosition)
    }

    override fun getItemCount(): Int = networks.size

    inner class NetworkViewHolder(
        private val binding: ItemPixieDustNetworkBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(network: IwWifiNetwork, isSelected: Boolean) {
            binding.apply {
                root.setOnClickListener {
                    setSelectedPosition(adapterPosition)
                    onNetworkClick(network)
                }

                textSsid.text = if (network.isHidden) {
                    root.context.getString(R.string.iw_wifi_hidden_network)
                } else {
                    network.ssid.ifEmpty { root.context.getString(R.string.pixiedust_hidden_network) }
                }

                textBssid.text = network.bssid

                val channelFreq = if (network.band.isNotEmpty()) {
                    root.context.getString(
                        R.string.iw_wifi_channel_frequency_band,
                        network.channel, network.frequency, network.band
                    )
                } else {
                    root.context.getString(
                        R.string.iw_wifi_channel_frequency,
                        network.channel, network.frequency
                    )
                }
                textFrequency.text = channelFreq

                val signalLevel = getSignalLevel(network.signalStrength)
                textSignal.text = root.context.getString(
                    R.string.iw_wifi_signal_strength,
                    network.signal,
                    signalLevel
                )

                textSecurityType.text = network.securityType

                updateWpsStatus(network)
                updatePixieDustStatus(network)
                updateSecurityIcon(network)

                updateSelectionBackground(isSelected)
            }
        }

        private fun getSignalLevel(signalStrength: Int): String {
            return when {
                signalStrength >= -40 -> binding.root.context.getString(R.string.iw_wifi_signal_excellent)
                signalStrength >= -55 -> binding.root.context.getString(R.string.iw_wifi_signal_good)
                signalStrength >= -65 -> binding.root.context.getString(R.string.iw_wifi_signal_fair)
                signalStrength >= -75 -> binding.root.context.getString(R.string.iw_wifi_signal_weak)
                else -> binding.root.context.getString(R.string.iw_wifi_signal_poor)
            }
        }

        private fun updateWpsStatus(network: IwWifiNetwork) {
            binding.apply {
                when {
                    network.wpsLocked -> {
                        textWpsStatus.text =
                            binding.root.context.getString(R.string.iw_wifi_wps_locked)
                        textWpsStatus.setTextColor(
                            ContextCompat.getColor(
                                root.context,
                                R.color.error_red
                            )
                        )
                        iconWps.setImageResource(R.drawable.ic_key)
                        iconWps.visibility = View.VISIBLE
                    }

                    network.wpsEnabled -> {
                        textWpsStatus.text =
                            binding.root.context.getString(R.string.iw_wifi_wps_available)
                        textWpsStatus.setTextColor(
                            ContextCompat.getColor(
                                root.context,
                                R.color.success_green
                            )
                        )
                        iconWps.setImageResource(R.drawable.ic_key)
                        iconWps.visibility = View.VISIBLE
                    }

                    else -> {
                        textWpsStatus.visibility = View.GONE
                        iconWps.visibility = View.GONE
                    }
                }
            }
        }

        private fun updatePixieDustStatus(network: IwWifiNetwork) {
            binding.apply {
                if (com.lsd.wififrankenstein.ui.iwwifi.PixieDustChecker.isPixieDustVulnerable(
                        network
                    )
                ) {
                    iconPixieDust.visibility = View.VISIBLE
                    textPixieDust.visibility = View.VISIBLE
                    textPixieDust.text = root.context.getString(R.string.pixie_dust_vulnerable)
                    (root as? MaterialCardView)?.setStrokeColor(
                        ContextCompat.getColor(root.context, R.color.error_red)
                    )
                } else {
                    iconPixieDust.visibility = View.GONE
                    textPixieDust.visibility = View.GONE
                    (root as? MaterialCardView)?.setStrokeColor(
                        ContextCompat.getColor(root.context, android.R.color.transparent)
                    )
                }
            }
        }

        private fun updateSecurityIcon(network: IwWifiNetwork) {
            binding.iconSecurity.setImageResource(
                if (network.securityType == "OPEN") R.drawable.ic_lock_open else R.drawable.ic_lock
            )
        }

        private fun updateSelectionBackground(isSelected: Boolean) {
            val background = binding.root.background as? GradientDrawable
            val context = binding.root.context
            if (background != null) {
                if (isSelected) {
                    background.setColor(ContextCompat.getColor(context, R.color.selected_card_bg))
                } else {
                    background.setColor(ContextCompat.getColor(context, android.R.color.white))
                }
            }
        }
    }

    private class NetworkDiffCallback(
        private val oldList: List<IwWifiNetwork>,
        private val newList: List<IwWifiNetwork>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(
            oldItemPosition: Int,
            newItemPosition: Int
        ): Boolean {
            return oldList[oldItemPosition].bssid == newList[newItemPosition].bssid
        }

        override fun areContentsTheSame(
            oldItemPosition: Int,
            newItemPosition: Int
        ): Boolean {
            val oldNetwork = oldList[oldItemPosition]
            val newNetwork = newList[newItemPosition]

            return oldNetwork.ssid == newNetwork.ssid &&
                    oldNetwork.signal == newNetwork.signal &&
                    oldNetwork.wpsEnabled == newNetwork.wpsEnabled &&
                    oldNetwork.wpsLocked == newNetwork.wpsLocked &&
                    oldNetwork.securityType == newNetwork.securityType
        }
    }
}
