package com.lsd.wififrankenstein.ui.wifianalysis

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemChannelAnalysisBinding

class ChannelAnalysisAdapter : ListAdapter<ChannelAnalysisData, ChannelAnalysisAdapter.ViewHolder>(DiffCallback()) {

    private var onLongClickListener: ((ChannelAnalysisData) -> Unit)? = null

    fun setOnLongClickListener(listener: (ChannelAnalysisData) -> Unit) {
        onLongClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelAnalysisBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onLongClickListener)
    }

    class ViewHolder(private val binding: ItemChannelAnalysisBinding) : RecyclerView.ViewHolder(binding.root) {
        private val networkAdapter = NetworkInChannelAdapter()
        private var isExpanded = false

        init {
            binding.recyclerViewNetworks.apply {
                layoutManager = LinearLayoutManager(itemView.context)
                adapter = networkAdapter
            }

            binding.layoutChannelHeader.setOnClickListener {
                toggleExpansion()
            }
        }

        fun bind(data: ChannelAnalysisData, onLongClickListener: ((ChannelAnalysisData) -> Unit)?) {
            binding.apply {
                textViewChannelNumber.text = data.channel.toString()
                textViewFrequency.text = itemView.context.getString(R.string.frequency_mhz, data.frequency)
                textViewNetworkCount.text = itemView.context.getString(R.string.access_points_count, data.networks.size)

                progressChannelLoad.progress = data.channelLoad
                textViewChannelLoad.text = itemView.context.getString(R.string.channel_load_percent, data.channelLoad)

                textViewStrongSignals.text = itemView.context.getString(R.string.strong_signals_short, data.strongSignalCount)
                textViewOverlapping.text = itemView.context.getString(R.string.overlapping_short, data.overlappingNetworks)

                val loadColor = when {
                    data.qualityScore >= 80 -> ContextCompat.getColor(itemView.context, R.color.green_500)
                    data.qualityScore >= 60 -> ContextCompat.getColor(itemView.context, R.color.orange_500)
                    else -> ContextCompat.getColor(itemView.context, R.color.red_500)
                }

                progressChannelLoad.setIndicatorColor(loadColor)

                networkAdapter.submitList(data.networks.sortedByDescending { it.scanResult.level })

                updateExpandIcon()

                root.setOnLongClickListener {
                    onLongClickListener?.invoke(data)
                    true
                }
            }
        }

        private fun toggleExpansion() {
            isExpanded = !isExpanded
            if (isExpanded) {
                binding.recyclerViewNetworks.visibility = android.view.View.VISIBLE
            } else {
                binding.recyclerViewNetworks.visibility = android.view.View.GONE
            }
            updateExpandIcon()
        }

        private fun updateExpandIcon() {
            val iconRes = if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            binding.imageViewExpandArrow.setImageResource(iconRes)
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ChannelAnalysisData>() {
        override fun areItemsTheSame(oldItem: ChannelAnalysisData, newItem: ChannelAnalysisData): Boolean {
            return oldItem.channel == newItem.channel && oldItem.band == newItem.band
        }

        override fun areContentsTheSame(oldItem: ChannelAnalysisData, newItem: ChannelAnalysisData): Boolean {
            return oldItem == newItem
        }
    }
}