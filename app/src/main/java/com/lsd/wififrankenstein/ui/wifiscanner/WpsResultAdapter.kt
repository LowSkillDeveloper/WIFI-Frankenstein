package com.lsd.wififrankenstein.ui.wifiscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemWpsResultBinding

class WpsResultAdapter : RecyclerView.Adapter<WpsResultAdapter.WpsResultViewHolder>() {

    private var results: List<NetworkDatabaseResult> = emptyList()

    fun submitList(newResults: List<NetworkDatabaseResult>) {
        results = newResults.filter { it.resultType == ResultType.WPS_ALGORITHM && it.wpsPin != null }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WpsResultViewHolder {
        val binding = ItemWpsResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WpsResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WpsResultViewHolder, position: Int) {
        holder.bind(results[position])
    }

    override fun getItemCount() = results.size

    inner class WpsResultViewHolder(private val binding: ItemWpsResultBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(result: NetworkDatabaseResult) {
            val wpsPin = result.wpsPin ?: return

            binding.algorithmName.text = wpsPin.name
            binding.wpsPinText.text = wpsPin.pin

            val source = wpsPin.additionalData["source"] as? String
            val distance = wpsPin.additionalData["distance"] as? String

            val sourceText = when {
                source == "neighbor_search" && distance != null ->
                    itemView.context.getString(R.string.source_format, "${wpsPin.name} (${distance} MAC distance)")
                source != null ->
                    itemView.context.getString(R.string.source_format, source)
                else -> ""
            }
            binding.sourceInfo.text = sourceText

            binding.scoreText.text = itemView.context.getString(R.string.score_format, wpsPin.score)

            if (wpsPin.sugg) {
                binding.statusIcon.setImageResource(R.drawable.ic_star)
                binding.statusIcon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.orange_dark))
            } else {
                binding.statusIcon.setImageResource(R.drawable.ic_help)
                binding.statusIcon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.orange_dark))
            }

            binding.experimentalChip.visibility = if (wpsPin.isExperimental) View.VISIBLE else View.GONE

            binding.copyPinButton.setOnClickListener {
                copyToClipboard(itemView.context, wpsPin.pin)
            }
        }

        private fun copyToClipboard(context: Context, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("WPS PIN", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.pin_copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }
}