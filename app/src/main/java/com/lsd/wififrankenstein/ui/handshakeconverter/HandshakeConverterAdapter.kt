package com.lsd.wififrankenstein.ui.handshakeconverter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemHandshakeConverterBinding

class HandshakeConverterAdapter(
    private val onTargetSelected: (String, TargetFormat) -> Unit,
    private val onRemove: (String) -> Unit
) : ListAdapter<ConvertFileItem, HandshakeConverterAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHandshakeConverterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemHandshakeConverterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ConvertFileItem) {
            val context = binding.root.context
            binding.textFileName.text = item.fileName

            val formatLabel = item.detectedFormat.name
            binding.textFileInfo.text = if (item.isSupported) {
                context.getString(
                    R.string.handshake_converter_format_info,
                    formatLabel, item.hash22000Lines.size
                )
            } else {
                context.getString(R.string.handshake_converter_unsupported, formatLabel)
            }

            binding.layoutTarget.visibility = if (item.isSupported) View.VISIBLE else View.GONE
            binding.textError.visibility = if (item.isSupported) View.GONE else View.VISIBLE
            binding.textError.text = item.error ?: ""

            val labels = item.availableTargets.map { context.getString(it.labelRes) }
            binding.spinnerTarget.adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_item, labels
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            binding.spinnerTarget.setSelection(
                item.availableTargets.indexOf(item.selectedTarget).coerceAtLeast(0),
                false
            )
            binding.spinnerTarget.setOnItemSelectedListener(
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        val target = item.availableTargets.getOrNull(position) ?: return
                        if (target != item.selectedTarget) onTargetSelected(item.id, target)
                    }

                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            )

            binding.btnRemove.setOnClickListener { onRemove(item.id) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ConvertFileItem>() {
            override fun areItemsTheSame(
                oldItem: ConvertFileItem,
                newItem: ConvertFileItem
            ): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: ConvertFileItem,
                newItem: ConvertFileItem
            ): Boolean = oldItem == newItem
        }
    }
}
