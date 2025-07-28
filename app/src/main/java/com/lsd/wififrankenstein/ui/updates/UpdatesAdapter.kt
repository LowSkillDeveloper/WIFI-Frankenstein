package com.lsd.wififrankenstein.ui.updates

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemFileUpdateBinding

class UpdatesAdapter(
    private val onUpdateClick: (FileUpdateInfo) -> Unit,
    private val onRevertClick: (FileUpdateInfo) -> Unit
) : ListAdapter<FileUpdateInfo, UpdatesAdapter.ViewHolder>(FileUpdateDiffCallback()) {

    private var progressMap: Map<String, Int> = emptyMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileUpdateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemFileUpdateBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(fileInfo: FileUpdateInfo) {
            binding.apply {
                textViewFileName.text = fileInfo.fileName
                textViewLocalVersion.text = root.context.getString(R.string.local_version, fileInfo.localVersion)
                textViewServerVersion.text = root.context.getString(R.string.server_version, fileInfo.serverVersion)
                textViewLocalSize.text = root.context.getString(R.string.local_size, fileInfo.localSize)

                if (fileInfo.needsUpdate) {
                    buttonUpdate.isEnabled = true
                    buttonUpdate.setOnClickListener { onUpdateClick(fileInfo) }
                    buttonRevert.visibility = View.GONE
                } else {
                    buttonUpdate.isEnabled = false
                    val shouldShowRevert = if (fileInfo.fileName == "RouterKeygen.dic") {
                        fileInfo.localVersion != "0.0"
                    } else {
                        fileInfo.localVersion != "1.0"
                    }

                    if (shouldShowRevert) {
                        buttonRevert.visibility = View.VISIBLE
                        buttonRevert.setOnClickListener { onRevertClick(fileInfo) }
                    } else {
                        buttonRevert.visibility = View.GONE
                    }
                }

                val progress = progressMap[fileInfo.fileName] ?: 0
                progressBarUpdate.progress = progress
                progressBarUpdate.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
            }
        }
    }

    fun updateProgress(newProgressMap: Map<String, Int>) {
        progressMap = newProgressMap
        notifyDataSetChanged()
    }

    class FileUpdateDiffCallback : DiffUtil.ItemCallback<FileUpdateInfo>() {
        override fun areItemsTheSame(oldItem: FileUpdateInfo, newItem: FileUpdateInfo): Boolean {
            return oldItem.fileName == newItem.fileName
        }

        override fun areContentsTheSame(oldItem: FileUpdateInfo, newItem: FileUpdateInfo): Boolean {
            return oldItem == newItem
        }
    }
}