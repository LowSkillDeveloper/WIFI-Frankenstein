package com.lsd.wififrankenstein.ui.updates

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemSmartLinkDbUpdateBinding

class SmartLinkDbUpdateAdapter(
    private val onUpdateClick: (SmartLinkDbUpdateInfo) -> Unit
) : ListAdapter<SmartLinkDbUpdateInfo, SmartLinkDbUpdateAdapter.ViewHolder>(SmartLinkDbUpdateDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSmartLinkDbUpdateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(private val binding: ItemSmartLinkDbUpdateBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(updateInfo: SmartLinkDbUpdateInfo) {
            binding.apply {
                textViewDbName.text = updateInfo.dbItem.type
                textViewLocalVersion.text = root.context.getString(R.string.local_version, updateInfo.dbItem.version)
                textViewServerVersion.text = root.context.getString(R.string.server_version, updateInfo.serverVersion)

                progressBarUpdate.visibility = if (updateInfo.isUpdating) View.VISIBLE else View.GONE
                progressBarUpdate.progress = updateInfo.updateProgress

                buttonUpdate.isEnabled = updateInfo.needsUpdate && !updateInfo.isUpdating
                buttonUpdate.text = if (updateInfo.isUpdating)
                    root.context.getString(R.string.updating)
                else
                    root.context.getString(R.string.update)
                buttonUpdate.setOnClickListener { onUpdateClick(updateInfo) }
            }
        }
    }

    class SmartLinkDbUpdateDiffCallback : DiffUtil.ItemCallback<SmartLinkDbUpdateInfo>() {
        override fun areItemsTheSame(oldItem: SmartLinkDbUpdateInfo, newItem: SmartLinkDbUpdateInfo): Boolean {
            return oldItem.dbItem.id == newItem.dbItem.id
        }

        override fun areContentsTheSame(oldItem: SmartLinkDbUpdateInfo, newItem: SmartLinkDbUpdateInfo): Boolean {
            return oldItem == newItem
        }
    }
}