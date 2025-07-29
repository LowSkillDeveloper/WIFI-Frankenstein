package com.lsd.wififrankenstein.ui.pixiedust

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R

class WpsNetworkAdapter(
    private val onNetworkSelected: (WpsNetwork) -> Unit
) : ListAdapter<WpsNetwork, WpsNetworkAdapter.NetworkViewHolder>(NetworkDiffCallback()) {

    private var selectedNetwork: WpsNetwork? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wps_network, parent, false)
        return NetworkViewHolder(view)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        val network = getItem(position)
        holder.bind(network, network == selectedNetwork)

        holder.itemView.setOnClickListener {
            val previousSelected = selectedNetwork
            selectedNetwork = network

            onNetworkSelected(network)

            notifyItemChanged(currentList.indexOf(previousSelected))
            notifyItemChanged(position)
        }
    }

    fun setSelectedNetwork(network: WpsNetwork?) {
        val previousIndex = selectedNetwork?.let { currentList.indexOf(it) } ?: -1
        val newIndex = network?.let { currentList.indexOf(it) } ?: -1

        selectedNetwork = network

        if (previousIndex >= 0) notifyItemChanged(previousIndex)
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    inner class NetworkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textViewSSID: TextView = itemView.findViewById(R.id.textViewSSID)
        private val textViewBSSID: TextView = itemView.findViewById(R.id.textViewBSSID)
        private val textViewSignal: TextView = itemView.findViewById(R.id.textViewSignal)
        private val textViewFrequency: TextView = itemView.findViewById(R.id.textViewFrequency)
        private val chipSecurity: TextView = itemView.findViewById(R.id.chipSecurity)
        private val chipWPS: TextView = itemView.findViewById(R.id.chipWPS)
        private val iconSelected: TextView = itemView.findViewById(R.id.iconSelected)

        fun bind(network: WpsNetwork, isSelected: Boolean) {
            textViewSSID.text = if (network.ssid.isNotBlank()) network.ssid else "Unknown Network"
            textViewBSSID.text = network.bssid
            textViewSignal.text = itemView.context.getString(R.string.pixiedust_signal_strength, network.level)
            textViewFrequency.text = itemView.context.getString(R.string.pixiedust_frequency, network.frequency)

            (chipSecurity as TextView).text = network.securityType
            chipWPS.visibility = if (network.isWpsEnabled) View.VISIBLE else View.GONE

            iconSelected.visibility = if (isSelected) View.VISIBLE else View.GONE

            val strokeWidth = if (isSelected) 3 else 1
            (itemView as com.google.android.material.card.MaterialCardView).strokeWidth = strokeWidth
        }
    }

    class NetworkDiffCallback : DiffUtil.ItemCallback<WpsNetwork>() {
        override fun areItemsTheSame(oldItem: WpsNetwork, newItem: WpsNetwork): Boolean {
            return oldItem.bssid == newItem.bssid
        }

        override fun areContentsTheSame(oldItem: WpsNetwork, newItem: WpsNetwork): Boolean {
            return oldItem == newItem
        }
    }
}