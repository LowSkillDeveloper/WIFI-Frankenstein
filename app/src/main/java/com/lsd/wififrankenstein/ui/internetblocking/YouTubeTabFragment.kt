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
import com.lsd.wififrankenstein.databinding.FragmentTabYoutubeBinding
import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.YouTubeCheckResult
import com.lsd.wififrankenstein.ui.internetblocking.model.YouTubeEndpointResult

class YouTubeTabFragment : Fragment() {
    private var _binding: FragmentTabYoutubeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InternetBlockingViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTabYoutubeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonCheckYoutube.setOnClickListener {
            viewModel.checkYoutube()
        }

        viewModel.isChecking.observe(viewLifecycleOwner) { checking ->
            binding.buttonCheckYoutube.isEnabled = !checking
            binding.progressBar.visibility = if (checking) View.VISIBLE else View.GONE
            binding.buttonCheckYoutube.text = if (checking) "Checking..." else "Run YouTube Check"
        }

        viewModel.youtubeResult.observe(viewLifecycleOwner) { result ->
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

    private fun bindResult(result: YouTubeCheckResult) {
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

        binding.endpointSummaryText.text =
            "${result.endpointReachableCount}/${result.endpointTotal} reachable"
        binding.endpointContainer.removeAllViews()
        for (ep in result.endpointResults) {
            binding.endpointContainer.addView(createEndpointRow(ep))
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

        val sourceParts = mutableListOf<String>()
        if (result.downloadUrlUsed != null) {
            val shortUrl = result.downloadUrlUsed.substringAfter("://").substringBeforeLast("/")
            sourceParts.add(shortUrl)
        }
        sourceParts.add("${result.endpointReachableCount}/${result.endpointTotal} endpoints")
        binding.sourceText.text = sourceParts.joinToString("  ·  ")
    }

    private fun createEndpointRow(ep: YouTubeEndpointResult): View {
        val ctx = requireContext()
        val row = layoutInflater.inflate(R.layout.item_youtube_endpoint, null) as LinearLayout

        val dot = row.findViewById<View>(R.id.endpointDot)
        val label = row.findViewById<TextView>(R.id.endpointLabel)
        val ipText = row.findViewById<TextView>(R.id.endpointIp)
        val statusText = row.findViewById<TextView>(R.id.endpointStatus)

        label.text = ep.label
        ipText.text = ep.ip

        if (ep.reachable) {
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
