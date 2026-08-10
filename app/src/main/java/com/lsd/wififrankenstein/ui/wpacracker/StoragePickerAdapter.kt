package com.lsd.wififrankenstein.ui.wpacracker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemStoragePickerBinding
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeItem

class StoragePickerAdapter(
    private val onItemClick: (HandshakeItem) -> Unit
) : ListAdapter<HandshakeItem, StoragePickerAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HandshakeItem>() {
            override fun areItemsTheSame(old: HandshakeItem, new: HandshakeItem): Boolean =
                old.filePath == new.filePath

            override fun areContentsTheSame(old: HandshakeItem, new: HandshakeItem): Boolean =
                old == new
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStoragePickerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemStoragePickerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HandshakeItem) {
            binding.pickerEssid.text = item.essid?.takeIf { it.isNotBlank() } ?: item.fileName
            binding.pickerBssid.text = item.bssid ?: ""

            val infoParts = mutableListOf<String>()
            infoParts.add(item.fileName)
            infoParts.add(item.formattedSize)
            if (item.handshakeCount > 1) {
                infoParts.add("${item.handshakeCount} handshakes")
            }
            binding.pickerFileInfo.text = infoParts.joinToString(" · ")

            binding.pickerIconPmkid.visibility = if (item.hasPmkid) android.view.View.VISIBLE
            else android.view.View.GONE

            binding.pickerIconCracked.visibility =
                if (item.crackedPassword != null) android.view.View.VISIBLE
                else android.view.View.GONE

            if (!item.fileExists) {
                val hasHashes = item.hash22000 != null || item.hashPmkid != null
                binding.pickerError.visibility = android.view.View.VISIBLE
                binding.pickerError.text = if (hasHashes) {
                    binding.root.context.getString(R.string.handshake_storage_picker_hash_only)
                } else {
                    "File not found"
                }
                binding.root.alpha = if (hasHashes) 0.85f else 0.5f
            } else if (item.handshakeCount == 0 && item.hash22000 == null && item.hashPmkid == null) {
                binding.pickerError.visibility = android.view.View.VISIBLE
                binding.pickerError.text = "No hashes — will parse on tap"
                binding.root.alpha = 0.7f
            } else {
                binding.pickerError.visibility = android.view.View.GONE
                binding.root.alpha = 1.0f
            }

            binding.root.setOnClickListener {
                if (item.fileExists || item.hash22000 != null || item.hashPmkid != null) onItemClick(
                    item
                )
            }
        }
    }
}
