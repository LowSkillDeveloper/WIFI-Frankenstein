package com.lsd.wififrankenstein.ui.internetblocking

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentTabTelegramBinding
import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.DcResult
import com.lsd.wififrankenstein.ui.internetblocking.model.TelegramCheckResult

class TelegramTabFragment : Fragment() {
    private var _binding: FragmentTabTelegramBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InternetBlockingViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTabTelegramBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonCheckTelegram.setOnClickListener {
            viewModel.checkTelegram()
        }

        viewModel.isChecking.observe(viewLifecycleOwner) { checking ->
            binding.buttonCheckTelegram.isEnabled = !checking
            binding.progressBar.visibility = if (checking) View.VISIBLE else View.GONE
            binding.buttonCheckTelegram.text = if (checking) "Checking..." else "Run Telegram Check"
        }

        viewModel.telegramResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                binding.resultCard.visibility = View.VISIBLE
                binding.emptyStateCard.visibility = View.GONE
                bindResult(result)
            } else {
                binding.resultCard.visibility = View.GONE
                binding.emptyStateCard.visibility = View.VISIBLE
            }
        }
    }

    private fun bindResult(result: TelegramCheckResult) {
        val ctx = requireContext()


        val bannerColor = result.status.colorRes()
        binding.statusBanner.setBackgroundColor(ContextCompat.getColor(ctx, bannerColor))

        val iconRes = when (result.status) {
            CheckStatus.Ok -> R.drawable.ic_check_circle
            CheckStatus.PartiallyBlocked, CheckStatus.Throttled -> R.drawable.ic_warning
            else -> R.drawable.ic_error
        }
        binding.statusIcon.setImageResource(iconRes)
        binding.statusLabel.text = result.status.label()

        val sec = result.totalDurationMs / 1000f
        binding.durationText.text = "${String.format("%.1f", sec)}s"


        binding.dcSummaryText.text = "${result.dcReachableCount}/${result.dcTotal} reachable"
        binding.dcContainer.removeAllViews()
        for (dc in result.dcResults) {
            binding.dcContainer.addView(createDcRow(dc))
        }


        if (result.downloadSpeedKbps != null) {
            binding.downloadText.text = "${String.format("%.1f", result.downloadSpeedKbps)} KB/s"
            binding.downloadText.setTextColor(ContextCompat.getColor(ctx, R.color.success_green))
            if (result.downloadBytes != null) {
                binding.downloadBytesText.text = formatBytes(result.downloadBytes)
                binding.downloadBytesText.visibility = View.VISIBLE
            }
        } else {
            binding.downloadText.text = "Not available"
            binding.downloadText.setTextColor(ContextCompat.getColor(ctx, R.color.error_red))
            binding.downloadBytesText.visibility = View.GONE
        }


        if (result.uploadSpeedKbps != null) {
            binding.uploadText.text = "${String.format("%.1f", result.uploadSpeedKbps)} KB/s"
            binding.uploadText.setTextColor(ContextCompat.getColor(ctx, R.color.success_green))
            if (result.uploadBytes != null) {
                binding.uploadBytesText.text = formatBytes(result.uploadBytes)
                binding.uploadBytesText.visibility = View.VISIBLE
            }
        } else {
            binding.uploadText.text = "Not available"
            binding.uploadText.setTextColor(ContextCompat.getColor(ctx, R.color.error_red))
            binding.uploadBytesText.visibility = View.GONE
        }


        val sourceParts = mutableListOf<String>()
        if (result.downloadUrlUsed != null) {
            sourceParts.add(result.downloadUrlUsed)
        }
        sourceParts.add("DC ${result.dcReachableCount}/${result.dcTotal}")
        binding.sourceText.text = sourceParts.joinToString("  ·  ")
    }

    private fun createDcRow(dc: DcResult): View {
        val ctx = requireContext()
        val row = layoutInflater.inflate(R.layout.item_telegram_dc, null) as LinearLayout

        val dot = row.findViewById<View>(R.id.dcDot)
        val label = row.findViewById<TextView>(R.id.dcLabel)
        val ipText = row.findViewById<TextView>(R.id.dcIp)
        val statusText = row.findViewById<TextView>(R.id.dcStatus)

        label.text = dc.label
        ipText.text = dc.ip

        if (dc.reachable) {
            val gd = dot.background as GradientDrawable
            gd.setColor(ContextCompat.getColor(ctx, R.color.success_green))
            statusText.text = "Reachable"
            statusText.setTextColor(ContextCompat.getColor(ctx, R.color.success_green))
        } else {
            val gd = dot.background as GradientDrawable
            gd.setColor(ContextCompat.getColor(ctx, R.color.error_red))
            statusText.text = "Unreachable"
            statusText.setTextColor(ContextCompat.getColor(ctx, R.color.error_red))
        }

        return row
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
