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
    private val onRevertClick: (FileUpdateInfo) -> Unit,
    private val onCancelClick: (FileUpdateInfo) -> Unit
) : ListAdapter<FileUpdateInfo, UpdatesAdapter.ViewHolder>(FileUpdateDiffCallback()) {

    private var progressMap: Map<String, Int> = emptyMap()
    private var activeDownloads: Set<String> = emptySet()

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

                val isDownloading = activeDownloads.contains(fileInfo.fileName)
                val progress = progressMap[fileInfo.fileName] ?: 0

                when {
                    isDownloading -> {
                        buttonUpdate.visibility = View.GONE
                        buttonCancel.visibility = View.VISIBLE
                        buttonRevert.visibility = View.GONE
                        buttonCancel.setOnClickListener { onCancelClick(fileInfo) }

                        progressBarUpdate.visibility = View.VISIBLE
                        progressBarUpdate.progress = progress
                    }
                    fileInfo.needsUpdate -> {
                        buttonUpdate.visibility = View.VISIBLE
                        buttonUpdate.isEnabled = true
                        buttonUpdate.setOnClickListener { onUpdateClick(fileInfo) }
                        buttonCancel.visibility = View.GONE
                        buttonRevert.visibility = View.GONE
                        progressBarUpdate.visibility = View.GONE
                    }
                    else -> {
                        buttonUpdate.visibility = View.VISIBLE
                        buttonUpdate.isEnabled = false
                        buttonCancel.visibility = View.GONE
                        progressBarUpdate.visibility = View.GONE

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
                }
            }
        }
    }

    fun updateProgress(newProgressMap: Map<String, Int>) {
        progressMap = newProgressMap
        notifyDataSetChanged()
    }

    fun updateActiveDownloads(newActiveDownloads: Set<String>) {
        activeDownloads = newActiveDownloads
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