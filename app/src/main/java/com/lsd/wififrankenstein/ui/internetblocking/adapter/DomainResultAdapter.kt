package com.lsd.wififrankenstein.ui.internetblocking.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.databinding.ItemDomainResultBinding
import com.lsd.wififrankenstein.ui.internetblocking.model.DomainCheckResult

class DomainResultAdapter :
    ListAdapter<DomainCheckResult, DomainResultAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(private val binding: ItemDomainResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(result: DomainCheckResult) {
            val ctx = binding.root.context
            binding.domain.text = result.domain
            binding.tls13Status.text = result.tls13Status.label(ctx)
            binding.tls12Status.text = result.tls12Status.label(ctx)
            binding.httpStatus.text = result.httpStatus.label(ctx)

            binding.tls13Status.setTextColor(
                ContextCompat.getColor(ctx, result.tls13Status.colorRes())
            )
            binding.tls12Status.setTextColor(
                ContextCompat.getColor(ctx, result.tls12Status.colorRes())
            )
            binding.httpStatus.setTextColor(
                ContextCompat.getColor(ctx, result.httpStatus.colorRes())
            )

            result.details?.let {
                binding.details.text = it
                binding.details.visibility = android.view.View.VISIBLE
            } ?: run {
                binding.details.visibility = android.view.View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDomainResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(currentList[position])
    }

    private object DiffCallback : DiffUtil.ItemCallback<DomainCheckResult>() {
        override fun areItemsTheSame(old: DomainCheckResult, new: DomainCheckResult) =
            old.domain == new.domain

        override fun areContentsTheSame(old: DomainCheckResult, new: DomainCheckResult) =
            old == new
    }
}
