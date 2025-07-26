package com.lsd.wififrankenstein.ui.wifiscanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemWpaResultBinding

class WpaResultAdapter : RecyclerView.Adapter<WpaResultAdapter.WpaResultViewHolder>() {

    private var results: List<NetworkDatabaseResult> = emptyList()

    fun submitList(newResults: List<NetworkDatabaseResult>) {
        results = newResults.filter { it.resultType == ResultType.WPA_ALGORITHM && it.wpaResult != null }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WpaResultViewHolder {
        val binding = ItemWpaResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WpaResultViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WpaResultViewHolder, position: Int) {
        holder.bind(results[position])
    }

    override fun getItemCount() = results.size

    inner class WpaResultViewHolder(private val binding: ItemWpaResultBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(result: NetworkDatabaseResult) {
            val wpaResult = result.wpaResult ?: return

            binding.algorithmName.text = wpaResult.algorithm
            binding.wpaKeysText.text = wpaResult.keys.joinToString("\n")
            binding.generationTime.text = itemView.context.getString(R.string.generation_time, wpaResult.generationTime)

            binding.copyKeysButton.setOnClickListener {
                copyToClipboard(itemView.context, wpaResult.keys.joinToString("\n"))
            }
        }

        private fun copyToClipboard(context: Context, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("WPA Keys", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.keys_copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }
}