package com.lsd.wififrankenstein.ui.routerscan

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.data.RouterScanResult
import com.lsd.wififrankenstein.databinding.RouterScanItemBinding

class RouterScanAdapter(
    private val onItemClick: (RouterScanResult) -> Unit,
    private val onShowFullOutput: (RouterScanResult) -> Unit = {},
    private val onUploadTo3WiFi: (RouterScanResult) -> Unit = {}
) : ListAdapter<RouterScanResult, RouterScanAdapter.ViewHolder>(RouterScanDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            RouterScanItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: RouterScanItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(result: RouterScanResult) {
            binding.root.setOnClickListener { onItemClick(result) }

            binding.textIp.text =
                binding.root.context.getString(R.string.rs_ip_port, result.ip, result.port)
            binding.textStatus.text = result.status

            val context = binding.root.context

            val accentColor = when (result.type) {
                1 -> ContextCompat.getColor(context, R.color.success_green)
                2 -> ContextCompat.getColor(context, R.color.error_red)
                else -> ContextCompat.getColor(context, R.color.warning_orange)
            }

            binding.statusIndicator.setBackgroundColor(accentColor)
            binding.textStatus.backgroundTintList = ColorStateList.valueOf(accentColor)


            if (result.ssid.isNotEmpty()) {
                binding.ssidLayout.visibility = android.view.View.VISIBLE
                binding.textSsid.text = result.ssid
            } else {
                binding.ssidLayout.visibility = android.view.View.GONE
            }

            if (result.bssid.isNotEmpty()) {
                binding.bssidLayout.visibility = android.view.View.VISIBLE
                binding.textBssid.text = result.bssid
            } else {
                binding.bssidLayout.visibility = android.view.View.GONE
            }

            if (result.auth.isNotEmpty()) {
                binding.authLayout.visibility = android.view.View.VISIBLE
                binding.textAuth.text = result.auth
            } else {
                binding.authLayout.visibility = android.view.View.GONE
            }

            if (result.sec.isNotEmpty()) {
                binding.secLayout.visibility = android.view.View.VISIBLE
                binding.textSec.text = result.sec
            } else {
                binding.secLayout.visibility = android.view.View.GONE
            }

            if (result.psk.isNotEmpty()) {
                binding.pskLayout.visibility = android.view.View.VISIBLE
                binding.textPsk.text = result.psk
            } else {
                binding.pskLayout.visibility = android.view.View.GONE
            }

            if (result.wps.isNotEmpty()) {
                binding.wpsLayout.visibility = android.view.View.VISIBLE
                binding.textWps.text = result.wps
            } else {
                binding.wpsLayout.visibility = android.view.View.GONE
            }

            if (result.title.isNotEmpty()) {
                binding.titleLayout.visibility = android.view.View.VISIBLE
                binding.textTitle.text = result.title
            } else {
                binding.titleLayout.visibility = android.view.View.GONE
            }

            if (result.serverType.isNotEmpty()) {
                binding.serverTypeLayout.visibility = android.view.View.VISIBLE
                binding.textServerType.text = result.serverType
            } else {
                binding.serverTypeLayout.visibility = android.view.View.GONE
            }


            val hasDetails = result.psk.isNotEmpty() || result.wps.isNotEmpty()
                    || result.title.isNotEmpty() || result.serverType.isNotEmpty()
            binding.divider.visibility =
                if (hasDetails) android.view.View.VISIBLE else android.view.View.GONE

            val hasFullOutput = result.fullOutput.isNotEmpty()
            val canUpload =
                result.success && (result.ssid.isNotEmpty() || result.bssid.isNotEmpty())

            binding.buttonUpload3wifi.visibility =
                if (canUpload) android.view.View.VISIBLE else android.view.View.GONE
            binding.buttonUpload3wifi.setOnClickListener {
                onUploadTo3WiFi(result)
            }

            binding.buttonFullOutput.visibility =
                if (hasFullOutput) android.view.View.VISIBLE else android.view.View.GONE
            binding.buttonFullOutput.setOnClickListener {
                onShowFullOutput(result)
            }

            binding.actionButtons.visibility =
                if (canUpload || hasFullOutput) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    class RouterScanDiffCallback : DiffUtil.ItemCallback<RouterScanResult>() {
        override fun areItemsTheSame(
            oldItem: RouterScanResult,
            newItem: RouterScanResult
        ): Boolean {
            return oldItem.ip == newItem.ip && oldItem.port == newItem.port
        }

        override fun areContentsTheSame(
            oldItem: RouterScanResult,
            newItem: RouterScanResult
        ): Boolean {
            return oldItem == newItem
        }
    }
}
