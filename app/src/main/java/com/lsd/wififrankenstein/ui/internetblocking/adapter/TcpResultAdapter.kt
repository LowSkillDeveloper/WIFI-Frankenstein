package com.lsd.wififrankenstein.ui.internetblocking.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.databinding.ItemTcpResultBinding
import com.lsd.wififrankenstein.ui.internetblocking.model.TcpCheckResult

class TcpResultAdapter : ListAdapter<TcpCheckResult, TcpResultAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(private val binding: ItemTcpResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(result: TcpCheckResult) {
            binding.provider.text = "${result.provider} (${result.id})"
            binding.ipPort.text = "${result.ip}:${result.port}"
            binding.status.text = result.status.label()
            binding.status.setTextColor(
                ContextCompat.getColor(binding.root.context, result.status.colorRes())
            )

            binding.details.text = buildString {
                append("Alive: ${if (result.alive) "Yes" else "No"}")
                result.rtt?.let { append("\nRTT: ${String.format("%.2f", it)}s") }
                result.blockKb?.let { append("\nBlock at: ${it}KB") }
                result.blockDetail?.let { append("\n$it") }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTcpResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(currentList[position])
    }

    private object DiffCallback : DiffUtil.ItemCallback<TcpCheckResult>() {
        override fun areItemsTheSame(old: TcpCheckResult, new: TcpCheckResult) =
            old.id == new.id

        override fun areContentsTheSame(old: TcpCheckResult, new: TcpCheckResult) =
            old == new
    }
}
