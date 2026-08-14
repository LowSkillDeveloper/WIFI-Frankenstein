package com.lsd.wififrankenstein.ui.internetblocking.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemSweepResultBinding
import com.lsd.wififrankenstein.ui.internetblocking.model.SweepResult

class SweepResultAdapter : ListAdapter<SweepResult, SweepResultAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(private val binding: ItemSweepResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(result: SweepResult) {
            val ctx = binding.root.context
            binding.provider.text = ctx.getString(R.string.ib_tcp_provider, result.targetProvider, result.targetId)
            binding.ipPort.text = ctx.getString(R.string.ib_sweep_ipport, result.targetIp, result.targetPort)
            binding.status.text = result.status.label(ctx)
            binding.status.setTextColor(
                ContextCompat.getColor(ctx, result.status.colorRes())
            )
            binding.workingSni.text = result.workingSni
            binding.rtt.text = ctx.getString(R.string.ib_sweep_rtt, result.rtt * 1000)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSweepResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(currentList[position])
    }

    private object DiffCallback : DiffUtil.ItemCallback<SweepResult>() {
        override fun areItemsTheSame(old: SweepResult, new: SweepResult) =
            old.targetId == new.targetId && old.workingSni == new.workingSni

        override fun areContentsTheSame(old: SweepResult, new: SweepResult) =
            old == new
    }
}
