package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.databinding.ItemLocalDbManagementBinding

class LocalDbManagementAdapter(
    private val onItemClick: (WifiNetwork) -> Unit,
    private val onItemLongClick: (WifiNetwork) -> Unit
) : PagingDataAdapter<WifiNetwork, LocalDbManagementAdapter.ViewHolder>(WifiNetworkDiffCallback()) {

    private val selectedItems = mutableSetOf<WifiNetwork>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLocalDbManagementBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        if (item != null) {
            holder.bind(item)
        }
    }

    inner class ViewHolder(private val binding: ItemLocalDbManagementBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WifiNetwork?) {
            item?.let { wifiNetwork ->
                binding.textViewWifiName.text = wifiNetwork.wifiName
                binding.textViewMacAddress.text = wifiNetwork.macAddress
            binding.textViewPassword.text = item.wifiPassword?.let { "Password: $it" } ?: "Password: N/A"
            binding.textViewWpsPin.text = item.wpsCode?.let { "WPS PIN: $it" } ?: "WPS PIN: N/A"
            binding.textViewAdminPanel.text = item.adminPanel?.let { "Admin Panel: $it" } ?: "Admin Panel: N/A"
            binding.textViewCoordinates.text = if (item.latitude != null && item.longitude != null) {
                "Coordinates: ${item.latitude}, ${item.longitude}"
            } else {
                "Coordinates: N/A"
            }
                binding.root.setOnClickListener { onItemClick(wifiNetwork) }
                binding.root.setOnLongClickListener {
                    onItemLongClick(wifiNetwork)
                    true
                }
                binding.root.isSelected = selectedItems.contains(wifiNetwork)
            }
        }
    }


    fun toggleSelection(item: WifiNetwork) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item)
        } else {
            selectedItems.add(item)
        }
        notifyItemChanged(snapshot().items.indexOf(item))
    }

    fun getSelectedItems(): List<WifiNetwork> = selectedItems.toList()

    @SuppressLint("NotifyDataSetChanged")
    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
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