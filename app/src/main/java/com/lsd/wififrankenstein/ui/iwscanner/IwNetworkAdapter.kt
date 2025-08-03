package com.lsd.wififrankenstein.ui.iwscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R

class IwNetworkAdapter(
    private val onNetworkClicked: (IwNetworkInfo) -> Unit
) : ListAdapter<IwNetworkInfo, IwNetworkAdapter.NetworkViewHolder>(NetworkDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_iw_network, parent, false)
        return NetworkViewHolder(view)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        val network = getItem(position)
        holder.bind(network)
        holder.itemView.setOnClickListener {
            onNetworkClicked(network)
        }
    }

    class NetworkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textSSID: TextView = itemView.findViewById(R.id.textSSID)
        private val textBSSID: TextView = itemView.findViewById(R.id.textBSSID)
        private val textSignal: TextView = itemView.findViewById(R.id.textSignal)
        private val textFrequency: TextView = itemView.findViewById(R.id.textFrequency)
        private val textChannel: TextView = itemView.findViewById(R.id.textChannel)
        private val textSecurity: TextView = itemView.findViewById(R.id.textSecurity)
        private val iconConnected: ImageView = itemView.findViewById(R.id.iconConnected)
        private val iconExpand: ImageView = itemView.findViewById(R.id.iconExpand)

        fun bind(network: IwNetworkInfo) {
            textSSID.text = if (network.ssid.isNotBlank()) network.ssid else itemView.context.getString(R.string.iw_hidden_ssid)
            textBSSID.text = network.bssid
            textSignal.text = if (network.signal.isNotBlank()) network.signal else itemView.context.getString(R.string.iw_unknown_signal)
            textFrequency.text = itemView.context.getString(R.string.iw_frequency_format, network.frequency)
            textChannel.text = itemView.context.getString(R.string.iw_channel_format, network.channel)

            val security = mutableListOf<String>()
            if (network.security.wpa.isNotBlank()) security.add("WPA")
            if (network.security.rsn.isNotBlank()) security.add("RSN")
            if (network.security.wps.isNotBlank()) security.add("WPS")

            textSecurity.text = if (security.isNotEmpty()) {
                security.joinToString(", ")
            } else {
                itemView.context.getString(R.string.iw_open_network)
            }

            iconConnected.visibility = if (network.isAssociated) View.VISIBLE else View.GONE
            iconExpand.setImageResource(R.drawable.ic_expand_more)
        }
    }

    class NetworkDiffCallback : DiffUtil.ItemCallback<IwNetworkInfo>() {
        override fun areItemsTheSame(oldItem: IwNetworkInfo, newItem: IwNetworkInfo): Boolean {
            return oldItem.bssid == newItem.bssid
        }

        override fun areContentsTheSame(oldItem: IwNetworkInfo, newItem: IwNetworkInfo): Boolean {
            return oldItem == newItem
        }
    }
}