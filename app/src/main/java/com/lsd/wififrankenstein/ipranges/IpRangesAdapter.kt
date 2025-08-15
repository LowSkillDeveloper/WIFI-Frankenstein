package com.lsd.wififrankenstein.ui.ipranges

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemIpRangeBinding

class IpRangesAdapter(
    private val onCopyClick: (IpRangeResult) -> Unit,
    private val onSelectionChanged: (List<IpRangeResult>) -> Unit
) : ListAdapter<IpRangeResult, IpRangesAdapter.ViewHolder>(DiffCallback()) {

    private val selectedItems = mutableSetOf<IpRangeResult>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemIpRangeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(currentList)
        notifyDataSetChanged()
        onSelectionChanged(selectedItems.toList())
    }

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
        onSelectionChanged(emptyList())
    }

    fun getSelectedItems(): List<IpRangeResult> = selectedItems.toList()

    inner class ViewHolder(
        private val binding: ItemIpRangeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(range: IpRangeResult) {
            binding.rangeText.text = range.range
            binding.sourceText.text = range.sourceName
            binding.descriptionText.text = range.description

            if (range.netname.isNotEmpty()) {
                binding.netnameText.text = range.netname
                binding.netnameText.visibility = View.VISIBLE
            } else {
                binding.netnameText.visibility = View.GONE
            }

            if (range.country.isNotEmpty()) {
                binding.countryText.text = "Country: ${range.country}"
                binding.countryText.visibility = View.VISIBLE
            } else {
                binding.countryText.visibility = View.GONE
            }

            binding.selectionCheckbox.isChecked = selectedItems.contains(range)

            val isSelected = selectedItems.contains(range)
            if (isSelected) {
                binding.cardView.setCardBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.grid_color)
                )
            } else {
                binding.cardView.setCardBackgroundColor(
                    ContextCompat.getColor(binding.root.context, android.R.color.transparent)
                )
            }

            binding.selectionCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedItems.add(range)
                } else {
                    selectedItems.remove(range)
                }
                onSelectionChanged(selectedItems.toList())

                if (isChecked) {
                    binding.cardView.setCardBackgroundColor(
                        ContextCompat.getColor(binding.root.context, R.color.grid_color)
                    )
                } else {
                    binding.cardView.setCardBackgroundColor(
                        ContextCompat.getColor(binding.root.context, android.R.color.transparent)
                    )
                }
            }

            binding.copyButton.setOnClickListener {
                onCopyClick(range)
            }

            binding.cardView.setOnClickListener {
                binding.selectionCheckbox.isChecked = !binding.selectionCheckbox.isChecked
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<IpRangeResult>() {
        override fun areItemsTheSame(oldItem: IpRangeResult, newItem: IpRangeResult): Boolean {
            return oldItem.range == newItem.range && oldItem.sourceName == newItem.sourceName
        }

        override fun areContentsTheSame(oldItem: IpRangeResult, newItem: IpRangeResult): Boolean {
            return oldItem == newItem
        }
    }
}