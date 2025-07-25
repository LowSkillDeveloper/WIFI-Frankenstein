package com.lsd.wififrankenstein.ui.wpsgenerator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemWpsGeneratorResultBinding

class WpsGeneratorAdapter : ListAdapter<WpsGeneratorResult, WpsGeneratorAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWpsGeneratorResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemWpsGeneratorResultBinding) : RecyclerView.ViewHolder(binding.root) {
        private val pinAdapter = PinAdapter()
        private var isExpanded = false

        init {
            binding.recyclerViewPins.apply {
                layoutManager = LinearLayoutManager(itemView.context)
                adapter = pinAdapter
            }

            binding.root.setOnClickListener {
                isExpanded = !isExpanded
                updateExpandedState()
            }
        }

        fun bind(result: WpsGeneratorResult) {
            binding.textViewSsid.text = result.ssid
            binding.textViewBssid.text = result.bssid
            binding.textViewPinCount.text = itemView.context.getString(R.string.pins_found_count, result.pins.size)

            val suggestedCount = result.pins.count { it.sugg }
            val possibleCount = result.pins.count { !it.sugg && shouldShowQuestionMark(it) }

            if (suggestedCount > 0) {
                binding.textViewSuggestedCount.visibility = View.VISIBLE
                binding.textViewSuggestedCount.text = itemView.context.getString(R.string.suggested_pins_count, suggestedCount)
            } else {
                binding.textViewSuggestedCount.visibility = View.GONE
            }

            if (possibleCount > 0) {
                binding.textViewPossibleCount.visibility = View.VISIBLE
                binding.textViewPossibleCount.text = itemView.context.getString(R.string.possible_pins_count, possibleCount)
            } else {
                binding.textViewPossibleCount.visibility = View.GONE
            }

            pinAdapter.submitList(result.pins)
            updateExpandedState()
        }

        private fun updateExpandedState() {
            binding.recyclerViewPins.visibility = if (isExpanded) View.VISIBLE else View.GONE
            binding.imageViewExpand.rotation = if (isExpanded) 180f else 0f
        }
    }

    inner class PinAdapter : ListAdapter<WPSPin, PinAdapter.PinViewHolder>(PinDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PinViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = inflater.inflate(R.layout.item_wps_pin_simple, parent, false)
            return PinViewHolder(view)
        }

        override fun onBindViewHolder(holder: PinViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class PinViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textPin = itemView.findViewById<android.widget.TextView>(R.id.text_pin)
            private val textAlgo = itemView.findViewById<android.widget.TextView>(R.id.text_algo)
            private val imageSuggested = itemView.findViewById<android.widget.ImageView>(R.id.image_suggested)
            private val imageQuestion = itemView.findViewById<android.widget.ImageView>(R.id.image_question)
            private val chipExperimental = itemView.findViewById<com.google.android.material.chip.Chip>(R.id.chip_experimental)

            init {
                itemView.setOnClickListener {
                    val pin = getItem(adapterPosition)
                    copyToClipboard(itemView.context, pin.pin)
                }
            }

            fun bind(pin: WPSPin) {
                textPin.text = pin.pin
                textAlgo.text = pin.name
                when {
                    pin.sugg -> {
                        imageSuggested.visibility = View.VISIBLE
                        imageQuestion.visibility = View.GONE
                    }
                    this@WpsGeneratorAdapter.shouldShowQuestionMark(pin) -> {
                        imageSuggested.visibility = View.GONE
                        imageQuestion.visibility = View.VISIBLE
                    }
                    else -> {
                        imageSuggested.visibility = View.GONE
                        imageQuestion.visibility = View.GONE
                    }
                }
                chipExperimental.visibility = if (pin.isExperimental) View.VISIBLE else View.GONE

                if (pin.additionalData.containsKey("source")) {
                    val source = pin.additionalData["source"] as? String
                    textAlgo.text = "${pin.name} ($source)"
                }
            }

            private fun copyToClipboard(context: Context, text: String) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("WPS PIN", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, context.getString(R.string.pin_copied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shouldShowQuestionMark(pin: WPSPin): Boolean {
        val source = pin.additionalData["source"] as? String
        val exactMatch = pin.additionalData["exact_match"] as? Boolean ?: false

        return when {
            pin.isFrom3WiFi && !exactMatch -> true
            source == "inapp_database" -> true
            source == "neighbor_search" && !pin.sugg -> true
            else -> false
        }
    }

    class PinDiffCallback : DiffUtil.ItemCallback<WPSPin>() {
        override fun areItemsTheSame(oldItem: WPSPin, newItem: WPSPin): Boolean {
            return oldItem.pin == newItem.pin && oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: WPSPin, newItem: WPSPin): Boolean {
            return oldItem == newItem
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<WpsGeneratorResult>() {
        override fun areItemsTheSame(oldItem: WpsGeneratorResult, newItem: WpsGeneratorResult): Boolean {
            return oldItem.bssid == newItem.bssid
        }

        override fun areContentsTheSame(oldItem: WpsGeneratorResult, newItem: WpsGeneratorResult): Boolean {
            return oldItem == newItem
        }
    }
}