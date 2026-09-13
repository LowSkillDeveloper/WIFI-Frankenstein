package com.lsd.wififrankenstein.ui.personalmap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentPersonalMapStatsBinding

class PersonalMapStatsFragment : Fragment() {

    private var _binding: FragmentPersonalMapStatsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PersonalWiFiMapViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalMapStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.statsBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.statsLoading?.visibility = View.VISIBLE
        binding.statsCards?.visibility = View.GONE

        viewModel.stats.observe(viewLifecycleOwner) { stats ->
            if (_binding == null || !isAdded) return@observe
            buildOverview(stats.total, stats.reliable, stats.wpsCount, stats.openCount)
            binding.statsGeneral.text = buildString {
                appendLine(getString(R.string.pm_stat_cross_count, stats.crossFound))
                appendLine(getString(R.string.pm_stat_wpasec_count, stats.wpasecFound))
                appendLine(getString(R.string.pm_stat_avg_accuracy, stats.avgAccuracy))
                appendLine(getString(R.string.pm_stat_first_seen, formatTime(stats.firstSeen)))
                append(getString(R.string.pm_stat_last_seen, formatTime(stats.lastSeen)))
            }
        }

        viewModel.getSecurityBreakdown { security ->
            if (_binding == null || !isAdded) return@getSecurityBreakdown
            binding.statsSecurity.text = if (security.isEmpty()) {
                getString(R.string.not_available)
            } else {
                val total = security.sumOf { it.second }
                security.joinToString("\n") { (type, count) ->
                    val pct = if (total > 0L) (count * 100 / total).toInt() else 0
                    getString(
                        R.string.pm_stat_row_bar,
                        securityLabel(type),
                        proportionBar(pct),
                        count
                    )
                }
            }
        }

        viewModel.getBandBreakdown { bands ->
            if (_binding == null || !isAdded) return@getBandBreakdown
            binding.statsBands.text = if (bands.isEmpty()) {
                getString(R.string.not_available)
            } else {
                val total = bands.sumOf { it.second }
                bands.joinToString("\n") { (band, count) ->
                    val pct = if (total > 0L) (count * 100 / total).toInt() else 0
                    getString(R.string.pm_stat_row_bar, band, proportionBar(pct), count)
                }
            }
        }

        viewModel.getVendorTop10 { vendors ->
            if (_binding == null || !isAdded) return@getVendorTop10
            binding.statsVendors.text = if (vendors.isEmpty()) {
                getString(R.string.not_available)
            } else {
                val total = vendors.sumOf { it.second }
                vendors.joinToString("\n") { (vendor, count) ->
                    val pct = if (total > 0L) (count * 100 / total).toInt() else 0
                    getString(R.string.pm_stat_row_bar, vendor, proportionBar(pct), count)
                }
            }
        }

        viewModel.getSsidPatternBreakdown { patterns ->
            if (_binding == null || !isAdded) return@getSsidPatternBreakdown
            binding.statsPatterns.text = if (patterns.isEmpty()) {
                getString(R.string.not_available)
            } else {
                val total = patterns.sumOf { it.second }
                patterns.joinToString("\n") { (pattern, count) ->
                    val pct = if (total > 0L) (count * 100 / total).toInt() else 0
                    getString(R.string.pm_stat_row_bar, pattern, proportionBar(pct), count)
                }
            }
        }

        viewModel.getChannelBreakdown { channels ->
            if (_binding == null || !isAdded) return@getChannelBreakdown

            val hasData = (viewModel.stats.value?.total ?: 0L) > 0L
            binding.statsLoading?.visibility = View.GONE
            binding.statsCards?.visibility = if (hasData) View.VISIBLE else View.GONE
            binding.statsEmpty?.visibility = if (hasData) View.GONE else View.VISIBLE

            binding.statsChannels.text = if (channels.isEmpty()) {
                getString(R.string.not_available)
            } else {
                channels.joinToString("\n") { (channel, count) ->
                    getString(R.string.pm_stat_channel_row, channel, count)
                }
            }
        }
    }

    private fun buildOverview(total: Long, reliable: Long, wps: Long, open: Long) {
        val container = binding.statsOverview ?: return
        val ctx = context ?: return
        container.removeAllViews()
        val values = listOf(total, reliable, wps, open)
        val colors = listOf(R.color.signal_good, R.color.signal_fair, R.color.blue_500, R.color.signal_poor)
        val labels = listOf(
            getString(R.string.stat_total_points),
            getString(R.string.stat_reliable),
            getString(R.string.badge_wps),
            getString(R.string.pm_stats_open)
        )
        values.forEachIndexed { index, value ->
            val card = MaterialCardView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = 8
                }
                radius = 24f
                cardElevation = 2f
                setCardBackgroundColor(ContextCompat.getColor(ctx, colors[index]))
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(8, 12, 8, 12)
            }
            val valueView = TextView(ctx).apply {
                text = java.text.NumberFormat.getIntegerInstance().format(value)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
                gravity = android.view.Gravity.CENTER
            }
            val labelView = TextView(ctx).apply {
                text = labels[index]
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
                gravity = android.view.Gravity.CENTER
                maxLines = 1
            }
            box.addView(valueView)
            box.addView(labelView)
            card.addView(box)
            container.addView(card)
        }
    }

    private fun proportionBar(percent: Int, width: Int = 10): String {
        val filled = ((percent.coerceIn(0, 100) * width) + 50) / 100
        return "█".repeat(filled) + "░".repeat((width - filled).coerceAtLeast(0))
    }

    private fun formatTime(value: Long): String =
        PersonalMapUtils.formatDateTime(value, resources)

    private fun securityLabel(type: String): String =
        PersonalMapUtils.securityLabel(type, resources)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
