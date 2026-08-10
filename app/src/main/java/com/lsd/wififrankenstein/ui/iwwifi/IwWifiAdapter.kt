package com.lsd.wififrankenstein.ui.iwwifi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemIwWifiNetworkBinding
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork

class IwWifiAdapter(
    private val onNetworkClick: (IwWifiNetwork) -> Unit
) : RecyclerView.Adapter<IwWifiAdapter.NetworkViewHolder>() {

    private var networks: List<IwWifiNetwork> = emptyList()

    fun updateNetworks(newNetworks: List<IwWifiNetwork>) {
        val diffCallback = NetworkDiffCallback(networks, newNetworks)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        networks = newNetworks
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val binding = ItemIwWifiNetworkBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NetworkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        holder.bind(networks[position])
    }

    override fun getItemCount(): Int = networks.size

    inner class NetworkViewHolder(
        private val binding: ItemIwWifiNetworkBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(network: IwWifiNetwork) {
            binding.apply {
                textSsid.text = if (network.isHidden) {
                    root.context.getString(R.string.iw_wifi_hidden_network)
                } else {
                    network.ssid
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

                textConnected.visibility = if (network.associated) View.VISIBLE else View.GONE

                root.setOnClickListener {
                    onNetworkClick(network)
                }
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
                        textWpsStatus.text = root.context.getString(R.string.iw_wifi_wps_locked)
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
                        textWpsStatus.text = root.context.getString(R.string.iw_wifi_wps_available)
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
                        textWpsStatus.text =
                            root.context.getString(R.string.iw_wifi_wps_not_supported)
                        textWpsStatus.setTextColor(
                            ContextCompat.getColor(
                                root.context,
                                R.color.text_secondary
                            )
                        )
                        iconWps.visibility = View.GONE
                    }
                }
            }
        }

        private fun updatePixieDustStatus(network: IwWifiNetwork) {
            binding.apply {
                if (PixieDustChecker.isPixieDustVulnerable(network)) {
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
    }

    private class NetworkDiffCallback(
        private val oldList: List<IwWifiNetwork>,
        private val newList: List<IwWifiNetwork>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].bssid == newList[newItemPosition].bssid
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldNetwork = oldList[oldItemPosition]
            val newNetwork = newList[newItemPosition]

            return oldNetwork.ssid == newNetwork.ssid &&
                    oldNetwork.signal == newNetwork.signal &&
                    oldNetwork.wpsEnabled == newNetwork.wpsEnabled &&
                    oldNetwork.wpsLocked == newNetwork.wpsLocked &&
                    oldNetwork.associated == newNetwork.associated
        }
    }
}