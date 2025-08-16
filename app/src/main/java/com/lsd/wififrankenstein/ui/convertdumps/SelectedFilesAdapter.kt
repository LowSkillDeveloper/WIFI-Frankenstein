package com.lsd.wififrankenstein.ui.convertdumps

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemSelectedFileBinding

class SelectedFilesAdapter(
    private val onRemoveClick: (Uri) -> Unit
) : ListAdapter<SelectedFile, SelectedFilesAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectedFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemSelectedFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: SelectedFile) {
            binding.textFileName.text = file.name
            binding.textFileType.text = when (file.type) {
                DumpFileType.ROUTERSCAN_TXT -> binding.root.context.getString(R.string.file_type_routerscan)
                DumpFileType.WIFI_3_SQL -> binding.root.context.getString(R.string.file_type_3wifi_sql)
                DumpFileType.P3WIFI_SQL -> binding.root.context.getString(R.string.file_type_p3wifi_sql)
                DumpFileType.UNKNOWN -> "Unknown"
            }

            binding.buttonRemove.setOnClickListener {
                onRemoveClick(file.uri)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<SelectedFile>() {
        override fun areItemsTheSame(oldItem: SelectedFile, newItem: SelectedFile): Boolean {
            return oldItem.uri == newItem.uri
        }

        override fun areContentsTheSame(oldItem: SelectedFile, newItem: SelectedFile): Boolean {
            return oldItem == newItem
        }
    }
}