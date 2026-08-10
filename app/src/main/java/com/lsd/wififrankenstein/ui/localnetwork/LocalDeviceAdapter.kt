package com.lsd.wififrankenstein.ui.localnetwork

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.LocalDeviceItemBinding

private val OS_ICON_MAP = mapOf(
    OSType.ANDROID to R.drawable.ic_dev_android,
    OSType.WINDOWS to R.drawable.ic_dev_windows,
    OSType.LINUX to R.drawable.bg_console,
    OSType.IOS to R.drawable.ic_dev_apple,
    OSType.MACOS to R.drawable.ic_dev_computer,
    OSType.PRINTER to R.drawable.ic_dev_printer,
    OSType.CAMERA to R.drawable.ic_dev_camera,
    OSType.ROUTER to R.drawable.router_24px,
    OSType.EMBEDDED to R.drawable.ic_dev_embedded,
    OSType.UNKNOWN to R.drawable.computer_24px,
    OSType.OTHER to R.drawable.computer_24px
)

class LocalDeviceAdapter(
    private val onDetails: (LocalDevice) -> Unit
) : ListAdapter<LocalDevice, LocalDeviceAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = LocalDeviceItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: LocalDeviceItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: LocalDevice) {
            val ctx = binding.root.context

            val iconRes = when {
                device.isGateway -> R.drawable.router_24px
                else -> OS_ICON_MAP[device.osType] ?: R.drawable.computer_24px
            }
            binding.imageDeviceIcon.setImageResource(iconRes)

            binding.textIp.text = device.ip

            val displayName = when {
                device.hostname.isNotEmpty() -> device.hostname
                device.netbiosName.isNotEmpty() -> device.netbiosName
                device.vendor.isNotEmpty() -> device.vendor
                else -> "Unknown device"
            }
            binding.textHostname.text = displayName

            if (device.mac.isNotEmpty()) {
                binding.macLayout.visibility = android.view.View.VISIBLE
                binding.textMac.text = device.mac
            } else {
                binding.macLayout.visibility = android.view.View.GONE
            }

            if (device.openPorts.isNotEmpty()) {
                binding.portsLayout.visibility = android.view.View.VISIBLE
                binding.textPorts.text = device.openPorts.joinToString(", ")
            } else {
                binding.portsLayout.visibility = android.view.View.GONE
            }

            if (device.os.isNotEmpty()) {
                binding.osLayout.visibility = android.view.View.VISIBLE
                binding.textOs.text = device.os
            } else {
                binding.osLayout.visibility = android.view.View.GONE
            }

            val dotColor = if (device.isAlive)
                ContextCompat.getColor(ctx, R.color.success_green)
            else
                ContextCompat.getColor(ctx, R.color.error_red)

            binding.statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
            binding.textStatus.text = if (device.isAlive) "Online" else "Offline"

            if (device.responseTimeMs > 0) {
                binding.textLatency.text = "${device.responseTimeMs}ms"
                binding.latencyLayout.visibility = android.view.View.VISIBLE
            } else {
                binding.latencyLayout.visibility = android.view.View.GONE
            }


            val ports = device.openPorts
            val hasWeb = ports.any { it == 80 || it == 443 || it == 8080 || it == 8443 }
            val hasSsh = ports.contains(22)
            val hasSmb = ports.contains(445) || ports.contains(21)

            binding.serviceIcons.visibility =
                if (hasWeb || hasSsh || hasSmb) android.view.View.VISIBLE else android.view.View.GONE
            binding.iconWeb.visibility =
                if (hasWeb) android.view.View.VISIBLE else android.view.View.GONE
            binding.iconSsh.visibility =
                if (hasSsh) android.view.View.VISIBLE else android.view.View.GONE
            binding.iconSmb.visibility =
                if (hasSmb) android.view.View.VISIBLE else android.view.View.GONE

            binding.root.setOnClickListener { onDetails(device) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<LocalDevice>() {
        override fun areItemsTheSame(oldItem: LocalDevice, newItem: LocalDevice): Boolean {
            return oldItem.ip == newItem.ip
        }

        override fun areContentsTheSame(oldItem: LocalDevice, newItem: LocalDevice): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        fun submitData(adapter: LocalDeviceAdapter, newItems: List<LocalDevice>) {
            adapter.submitList(newItems)
        }
    }
}
