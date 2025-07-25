package com.lsd.wififrankenstein.ui.wpagenerator

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemResultBinding
import com.lsd.wififrankenstein.databinding.ItemResultHeaderBinding

class ResultsAdapter(
    private val onKeyClick: (String) -> Unit
) : ListAdapter<ResultsAdapter.ResultItem, RecyclerView.ViewHolder>(ResultDiffCallback()) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_KEY = 1
        private const val COLLAPSED_KEYS_COUNT = 5
    }

    private val expandedStates = mutableMapOf<String, Boolean>()

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ResultItem.Header -> TYPE_HEADER
            is ResultItem.Key -> TYPE_KEY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val binding = ItemResultHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                HeaderViewHolder(binding)
            }
            TYPE_KEY -> {
                val binding = ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                KeyViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind(getItem(position) as ResultItem.Header)
            is KeyViewHolder -> holder.bind(getItem(position) as ResultItem.Key)
        }
    }

    fun updateResults(results: List<WpaResult>) {
        val items = mutableListOf<ResultItem>()

        for (result in results) {
            items.add(ResultItem.Header(result))

            val isExpanded = expandedStates[result.algorithm] ?: (result.keys.size <= COLLAPSED_KEYS_COUNT)
            val keysToShow = if (isExpanded) result.keys else result.keys.take(COLLAPSED_KEYS_COUNT)

            keysToShow.forEach { key ->
                items.add(ResultItem.Key(key))
            }
        }

        submitList(items)
    }

    inner class HeaderViewHolder(private val binding: ItemResultHeaderBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(header: ResultItem.Header) {
            val result = header.result
            binding.algorithmText.text = binding.root.context.getString(R.string.algorithm_used, result.algorithm)
            binding.keysCountText.text = "${result.keys.size} keys"
            binding.generationTimeText.text = binding.root.context.getString(R.string.generation_time, result.generationTime)

            val supportText = when (result.supportState) {
                WpaResult.SUPPORTED -> "Supported"
                WpaResult.UNLIKELY_SUPPORTED -> "Unlikely"
                else -> "Unsupported"
            }
            binding.supportStateText.text = supportText

            val supportColor = when (result.supportState) {
                WpaResult.SUPPORTED -> binding.root.context.getColor(android.R.color.holo_green_dark)
                WpaResult.UNLIKELY_SUPPORTED -> binding.root.context.getColor(android.R.color.holo_orange_dark)
                else -> binding.root.context.getColor(android.R.color.holo_red_dark)
            }
            binding.supportStateText.setTextColor(supportColor)

            // Показать кнопку развертывания только если ключей больше чем COLLAPSED_KEYS_COUNT
            if (result.keys.size > COLLAPSED_KEYS_COUNT) {
                val isExpanded = expandedStates[result.algorithm] ?: false
                binding.expandButton.visibility = View.VISIBLE
                binding.expandButton.setImageResource(
                    if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                )

                binding.expandButton.setOnClickListener {
                    expandedStates[result.algorithm] = !isExpanded
                    updateResults(listOf(result)) // Обновить только этот результат
                }
            } else {
                binding.expandButton.visibility = View.GONE
            }
        }
    }

    inner class KeyViewHolder(private val binding: ItemResultBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(keyItem: ResultItem.Key) {
            binding.keyText.text = keyItem.key
            binding.keyText.setOnClickListener {
                onKeyClick(keyItem.key)
            }

            binding.copyButton.setOnClickListener {
                onKeyClick(keyItem.key)
            }
        }
    }

    sealed class ResultItem {
        data class Header(val result: WpaResult) : ResultItem()
        data class Key(val key: String) : ResultItem()
    }

    private class ResultDiffCallback : DiffUtil.ItemCallback<ResultItem>() {
        override fun areItemsTheSame(oldItem: ResultItem, newItem: ResultItem): Boolean {
            return when {
                oldItem is ResultItem.Header && newItem is ResultItem.Header ->
                    oldItem.result.algorithm == newItem.result.algorithm
                oldItem is ResultItem.Key && newItem is ResultItem.Key ->
                    oldItem.key == newItem.key
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ResultItem, newItem: ResultItem): Boolean {
            return oldItem == newItem
        }
    }
}