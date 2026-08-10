package com.lsd.wififrankenstein.ui.internetblocking.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.databinding.ItemDnsResultBinding
import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.DnsCheckResult
import kotlinx.serialization.json.JsonElement

class DnsResultAdapter : ListAdapter<DnsCheckResult, DnsResultAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(private val binding: ItemDnsResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var expanded = false

        fun bind(result: DnsCheckResult) {
            binding.domain.text = result.domain
            binding.status.text = result.status.label()

            val colorRes = result.status.colorRes()
            binding.status.setTextColor(
                ContextCompat.getColor(binding.root.context, colorRes)
            )


            binding.udpIpsContainer.removeAllViews()
            if (result.udpIps.isNotEmpty()) {
                result.udpIps.forEach { ip ->
                    val chip = createIpChip(binding.root.context, ip, isFakeIp(ip))
                    binding.udpIpsContainer.addView(chip)
                }
            } else {
                binding.udpIpsContainer.visibility = android.view.View.GONE
            }


            binding.udpStatus.text = result.udpStatus?.let { "Статус: $it" }


            binding.jsonIpsContainer.removeAllViews()
            if (result.jsonIps.isNotEmpty()) {
                result.jsonIps.forEach { ip ->
                    val chip = createIpChip(binding.root.context, ip, false)
                    binding.jsonIpsContainer.addView(chip)
                }
            } else {
                binding.jsonIpsContainer.visibility = android.view.View.GONE
            }


            binding.jsonStatus.text = result.jsonStatus?.let { "Статус: $it" }


            binding.wireIpsContainer.removeAllViews()
            if (result.wireIps.isNotEmpty()) {
                result.wireIps.forEach { ip ->
                    val chip = createIpChip(binding.root.context, ip, false)
                    binding.wireIpsContainer.addView(chip)
                }
            } else {
                binding.wireIpsContainer.visibility = android.view.View.GONE
            }


            binding.wireStatus.text = result.wireStatus?.let { "Статус: $it" }


            val analysis = when (result.status) {
                CheckStatus.Ok -> "✓ IP совпадают — DNS работает корректно"
                CheckStatus.DnsSpoof -> "✗ IP не совпадают — обнаружена подмена DNS"
                CheckStatus.FakeIp -> "⚠ Фейковый IP (198.18.0.0/15) — вероятно VPN"
                CheckStatus.DnsIntercept -> "✗ UDP заблокирован, DoH работает — DNS перехват"
                CheckStatus.FakeNxdomain -> "✗ Сервер вернул NXDOMAIN для существующего домена"
                CheckStatus.FakeEmpty -> "✗ Сервер вернул пустой ответ вместо IP"
                CheckStatus.DohBlocked -> "✗ DoH заблокирован, UDP работает — блокировка DoH"
                else -> "⚠ Статус: ${result.status.label()}"
            }
            binding.analysisText.text = analysis


            if (result.totalUniqueIps > 0) {
                binding.extraInfo.visibility = android.view.View.VISIBLE
                binding.extraInfo.text = "Уник. IP: ${result.totalUniqueIps}"
            } else {
                binding.extraInfo.visibility = android.view.View.GONE
            }


            if (!result.jsonIps.isNullOrEmpty() && result.jsonRawResponse != null) {
                binding.jsonMoreInfo.visibility = android.view.View.VISIBLE
                binding.jsonMoreInfo.setOnClickListener {
                    val dialogView = LayoutInflater.from(binding.root.context)
                        .inflate(com.lsd.wififrankenstein.R.layout.dialog_dns_json, null)
                    val textView =
                        dialogView.findViewById<TextView>(com.lsd.wififrankenstein.R.id.jsonText)
                    val formattedJson = try {
                        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                            .parseToJsonElement(result.jsonRawResponse)
                        formatJson(json, 0)
                    } catch (_: Exception) {
                        result.jsonRawResponse
                    }
                    textView.text = formattedJson
                    MaterialAlertDialogBuilder(binding.root.context)
                        .setTitle("DoH JSON ответ")
                        .setView(dialogView)
                        .setPositiveButton("Закрыть", null)
                        .create()
                        .show()
                }
            } else {
                binding.jsonMoreInfo.visibility = android.view.View.GONE
            }


            binding.detailsContainer.visibility =
                if (expanded) android.view.View.VISIBLE else android.view.View.GONE
            binding.expandIcon.rotation = if (expanded) 180f else 0f

            binding.header.setOnClickListener {
                expanded = !expanded
                binding.detailsContainer.visibility =
                    if (expanded) android.view.View.VISIBLE else android.view.View.GONE
                binding.expandIcon.rotation = if (expanded) 180f else 0f
            }
        }

        private fun createIpChip(
            context: android.content.Context,
            ip: String,
            isFake: Boolean
        ): TextView {
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(if (isFake) Color.parseColor("#FFEBEE") else Color.parseColor("#E3F2FD"))
            }
            return TextView(context).apply {
                text = ip
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setCompoundDrawablePadding(8)
                background = drawable
                setTextColor(if (isFake) Color.parseColor("#D32F2F") else Color.parseColor("#1565C0"))
                setPadding(12, 6, 12, 6)
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 4, 8, 4)
                }
            }
        }

        private fun isFakeIp(ip: String): Boolean {
            return try {
                val addr = java.net.InetAddress.getByName(ip)
                val bytes = addr.address
                bytes != null && bytes.size == 4 &&
                        bytes[0] == 198.toByte() &&
                        (bytes[1] == 18.toByte() || bytes[1] == 19.toByte())
            } catch (_: Exception) {
                false
            }
        }

        private fun formatJson(json: JsonElement, indent: Int): String {
            val sb = StringBuilder()
            val prefix = "  ".repeat(indent)
            val innerPrefix = "  ".repeat(indent + 1)

            when (json) {
                is kotlinx.serialization.json.JsonObject -> {
                    sb.append("{\n")
                    val entries = json.entries.toList()
                    entries.forEachIndexed { i, (key, value) ->
                        sb.append("${innerPrefix}\"${key}\": ${formatJson(value, indent + 1)}")
                        if (i < entries.size - 1) sb.append(",")
                        sb.append("\n")
                    }
                    sb.append("${prefix}}")
                }

                is kotlinx.serialization.json.JsonArray -> {
                    sb.append("[\n")
                    json.forEachIndexed { i, element ->
                        sb.append("${innerPrefix}${formatJson(element, indent + 1)}")
                        if (i < json.size - 1) sb.append(",")
                        sb.append("\n")
                    }
                    sb.append("${prefix}]")
                }

                is kotlinx.serialization.json.JsonPrimitive -> {
                    val v = json.content
                    if (v.matches(Regex("\\d+\\.\\d+"))) {
                        sb.append(v)
                    } else if (v.matches(Regex("\\d+"))) {
                        sb.append(v)
                    } else {
                        sb.append("\"${v.replace("\"", "\\\"")}\"")
                    }
                }

                else -> sb.append(json.toString())
            }
            return sb.toString()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDnsResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(currentList[position])
    }

    private object DiffCallback : DiffUtil.ItemCallback<DnsCheckResult>() {
        override fun areItemsTheSame(old: DnsCheckResult, new: DnsCheckResult) =
            old.domain == new.domain

        override fun areContentsTheSame(old: DnsCheckResult, new: DnsCheckResult) =
            old == new
    }
}
