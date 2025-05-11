package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.databinding.ItemLocalDbRecordBinding

class LocalDbRecordAdapter(private val onItemClick: (WifiNetwork) -> Unit) :
    ListAdapter<WifiNetwork, LocalDbRecordAdapter.ViewHolder>(WifiNetworkDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLocalDbRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemLocalDbRecordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                onItemClick(getItem(adapterPosition))
            }
        }

        fun bind(wifiNetwork: WifiNetwork) {
            binding.textViewWifiName.text = wifiNetwork.wifiName
            binding.textViewMacAddress.text = wifiNetwork.macAddress
            binding.textViewAdminPanel.text = wifiNetwork.adminPanel

            val locationText = "Latitude: ${wifiNetwork.latitude}, Longitude: ${wifiNetwork.longitude}"
            binding.textViewLocation.text = locationText

            val additionalInfoText = "Password: ${wifiNetwork.wifiPassword ?: "N/A"}, WPS: ${wifiNetwork.wpsCode ?: "N/A"}"
            binding.textViewAdditionalInfo.text = additionalInfoText
        }
    }

    class WifiNetworkDiffCallback : DiffUtil.ItemCallback<WifiNetwork>() {
        override fun areItemsTheSame(oldItem: WifiNetwork, newItem: WifiNetwork): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: WifiNetwork, newItem: WifiNetwork): Boolean {
            return oldItem == newItem
        }
    }
}