package com.lsd.wififrankenstein.ui.personalmap

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.ItemDbMatchBinding

class DbMatchAdapter(
    private val onItemClick: (DbMatchEntry) -> Unit
) : ListAdapter<DbMatchEntry, DbMatchAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDbMatchBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemDbMatchBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClick(getItem(pos))
                }
            }
        }

        fun bind(item: DbMatchEntry) {
            val context = binding.root.context
            binding.matchSource.text = item.source
            binding.matchColorIndicator.visibility =
                if (item.color != 0) View.VISIBLE else View.GONE
            if (item.color != 0) {
                binding.matchColorIndicator.setBackgroundColor(item.color)
            }
            binding.matchSsid.text = item.ssid.ifBlank {
                context.getString(R.string.unknown_ssid)
            }
            binding.matchBssid.text = item.bssid
            if (!item.password.isNullOrBlank()) {
                binding.matchPassword.visibility = View.VISIBLE
                binding.matchNoPassword.visibility = View.GONE
                binding.matchPassword.text = context.getString(
                    R.string.pm_match_password_row, item.password
                )
            } else if (!item.wps.isNullOrBlank()) {
                binding.matchPassword.visibility = View.VISIBLE
                binding.matchNoPassword.visibility = View.GONE
                binding.matchPassword.text = context.getString(
                    R.string.pm_match_wps_row, item.wps
                )
            } else {
                binding.matchPassword.visibility = View.GONE
                binding.matchNoPassword.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DbMatchEntry>() {
            override fun areItemsTheSame(a: DbMatchEntry, b: DbMatchEntry) =
                a.source == b.source && a.bssid.equals(b.bssid, ignoreCase = true)

            override fun areContentsTheSame(a: DbMatchEntry, b: DbMatchEntry) = a == b
        }
    }
}
