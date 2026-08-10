package com.lsd.wififrankenstein.ui.internetblocking

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentTabMainBinding
import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus
import com.lsd.wififrankenstein.ui.internetblocking.model.MainTabResult
import com.lsd.wififrankenstein.ui.internetblocking.model.StageTrace
import com.lsd.wififrankenstein.ui.pixiedust.ConsoleAdapter

class MainTabFragment : Fragment() {
    private var _binding: FragmentTabMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: InternetBlockingViewModel by activityViewModels()

    private var consoleAdapter: ConsoleAdapter? = null
    private var consoleExpanded = false

    private companion object {

        val LEGEND_TEXT = """
            SYN DROP — SYN не доходит: пакеты роняются (blackhole)
            TLS RST — активный сброс TLS (TCP RST на handshake)
            TLS DROP — TLS-хендшейк зависает (пакеты роняются)
            TLS ALERT — TLS alert от DPI (SNI-блок / handshake failure)
            TLS MITM — подмена сертификата (MITM)
            TLS SPOOF — подмена TLS-ответа (wrong version / garbage)
            DNS ПОДМЕНА — DNS отвечает заглушками (UDP ≠ DoH)
            DNS ПЕРЕХВАТ — DNS UDP не отвечает, DoH отвечает
            FAKE-IP — DNS вернул 198.18.x.x (VPN fakeip)
            ISP PAGE — DNS вернул IP-заглушку провайдера
            TCP16-20 — обрыв после ~16-20KB (DPI-троттлинг)
            HTTP 451 — недоступно по юридическим причинам
            BLOCKED — HTTP 451 / cross-domain redirect / заглушка-страница
            REFUSED — соединение активно отклонено (RST)
        """.trimIndent()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTabMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonCheck.setOnClickListener {
            val domain = binding.editTextDomain.text?.toString()?.trim() ?: ""
            if (domain.isBlank()) {
                Toast.makeText(requireContext(), "Enter a domain", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.checkMainDomain(domain)
        }

        viewModel.isChecking.observe(viewLifecycleOwner) { checking ->
            binding.buttonCheck.isEnabled = !checking
            binding.progressBar.visibility = if (checking) View.VISIBLE else View.GONE
            binding.buttonCheck.text = if (checking) "Checking..." else "Check Domain"
        }

        viewModel.progressText.observe(viewLifecycleOwner) { text ->
            if (text.isNullOrBlank()) {
                binding.progressText.visibility = View.GONE
            } else {
                binding.progressText.visibility = View.VISIBLE
                binding.progressText.text = text
            }
        }

        viewModel.consoleLines.observe(viewLifecycleOwner) { lines ->
            if (lines.isNotEmpty()) {
                binding.cardConsole.isVisible = true
                if (consoleAdapter == null) {
                    consoleAdapter = ConsoleAdapter(autoScroll = true).also {
                        binding.recyclerConsole.layoutManager =
                            LinearLayoutManager(requireContext())
                        binding.recyclerConsole.adapter = it
                        it.attachToRecyclerView(binding.recyclerConsole)
                    }
                }
                consoleAdapter?.setLines(lines)
            } else if (consoleAdapter != null) {
                consoleAdapter?.setLines(emptyList())
            }
        }

        binding.layoutConsoleHeader.setOnClickListener {
            consoleExpanded = !consoleExpanded
            binding.recyclerConsole.visibility = if (consoleExpanded) View.VISIBLE else View.GONE
            binding.iconToggleConsole.setImageResource(
                if (consoleExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }

        binding.legendText.text = LEGEND_TEXT
        var legendExpanded = false
        binding.layoutLegendHeader.setOnClickListener {
            legendExpanded = !legendExpanded
            binding.legendText.visibility = if (legendExpanded) View.VISIBLE else View.GONE
            binding.iconToggleLegend.setImageResource(
                if (legendExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }

        viewModel.mainTabResult.observe(viewLifecycleOwner) { result ->
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

    private fun bindResult(r: MainTabResult) {
        val ctx = requireContext()


        val bannerColor = r.overallStatus.colorRes()
        binding.statusBanner.setBackgroundColor(ContextCompat.getColor(ctx, bannerColor))

        val iconRes = when (r.overallStatus) {
            CheckStatus.Ok -> R.drawable.ic_check_circle
            CheckStatus.PartiallyBlocked, CheckStatus.Throttled -> R.drawable.ic_warning
            else -> R.drawable.ic_error
        }
        binding.statusIcon.setImageResource(iconRes)
        binding.statusLabel.text = r.overallStatus.label()

        val sec = r.totalDurationMs / 1000f
        binding.durationText.text = "${String.format("%.1f", sec)}s"


        binding.domainText.text = "Domain: ${r.domain}"
        binding.resolvedIpText.text = "Resolved IP: ${r.resolvedIp ?: "unresolvable"}"


        binding.conclusionText.text = r.conclusion.ifBlank { "—" }
        val isError = r.overallStatus == CheckStatus.Error
        val isBlocked = r.blockStage != "—" && r.blockStage != "Error" && !isError
        val stageColor =
            if (isError) R.color.text_secondary
            else if (isBlocked) R.color.error_red
            else R.color.success_green
        val stageText = buildString {
            append("Этап блока: ${r.blockStage} · ${r.blockMechanism}")
            if (r.confidence.isNotBlank() && r.confidence != "—") append(" (${r.confidence})")
        }.trimEnd(' ', '·', '—')
        binding.blockStageText.text = stageText
        binding.blockStageText.setTextColor(ContextCompat.getColor(ctx, stageColor))


        val dnsOk =
            r.dnsStatus == CheckStatus.Ok || r.dnsStatus == CheckStatus.NotBlocked || r.dnsStatus == CheckStatus.Redirect
        setDotColor(binding.dnsDot, if (dnsOk) R.color.success_green else R.color.error_red)
        binding.dnsStatusText.text = r.dnsStatus.label()
        binding.dnsStatusText.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (dnsOk) R.color.success_green else R.color.error_red
            )
        )

        val ips = mutableListOf<String>()
        if (r.udpIps.isNotEmpty()) ips.add("UDP: ${r.udpIps.joinToString(", ")}")
        if (r.dohIps.isNotEmpty()) ips.add("DoH: ${r.dohIps.joinToString(", ")}")
        binding.dnsIpsText.text = ips.joinToString("\n")

        binding.dnsDetailText.visibility = View.GONE
        r.dnsDetails?.let {
            binding.dnsDetailText.text = it
            binding.dnsDetailText.visibility = View.VISIBLE
        }


        setTlsRow(binding.tls13Dot, binding.tls13Text, r.tls13Status)
        setTlsRow(binding.tls12Dot, binding.tls12Text, r.tls12Status)
        setTlsRow(binding.httpDot, binding.httpText, r.httpStatus)

        bindTlsDetail(binding.tls13DetailText, r.tls13Detail, r.tls13Trace)
        bindTlsDetail(binding.tls12DetailText, r.tls12Detail, r.tls12Trace)
        bindTlsDetail(binding.httpDetailText, r.httpDetail, r.httpTrace)


        if (r.tcpReachable) {
            val gd = binding.tcpDot.background as GradientDrawable
            gd.setColor(ContextCompat.getColor(ctx, R.color.success_green))
            val latency = r.tcpLatencyMs?.let { "${it}ms" } ?: ""
            binding.tcpText.text = "Reachable $latency"
            binding.tcpText.setTextColor(ContextCompat.getColor(ctx, R.color.success_green))
        } else {
            val gd = binding.tcpDot.background as GradientDrawable
            gd.setColor(ContextCompat.getColor(ctx, R.color.error_red))
            binding.tcpText.text = "Unreachable"
            binding.tcpText.setTextColor(ContextCompat.getColor(ctx, R.color.error_red))
        }
        val portLine = buildString {
            append("Порты: 80 ${if (r.port80Reachable) "открыт" else "заблокирован"}")
            append(" · baseline ${if (r.baselineReachable) "ok" else "down"}")
            if (r.sniBlocked) append(" · SNI: blocked")
        }
        binding.tcpText.append("  ·  " + portLine)


        val tcp16Ok = r.tcp16Status == CheckStatus.NotBlocked || r.tcp16Status == CheckStatus.Ok
        val tcp16Timeout = r.tcp16Status == CheckStatus.ReadTimeout
        setDotColor(
            binding.tcp16Dot,
            if (tcp16Ok) R.color.success_green else if (tcp16Timeout) R.color.warning_orange else R.color.error_red
        )
        binding.tcp16Text.text = r.tcp16Status.label()
        binding.tcp16Text.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (tcp16Ok) R.color.success_green else if (tcp16Timeout) R.color.warning_orange else R.color.error_red
            )
        )

        binding.tcp16DetailText.visibility = View.GONE
        if (r.tcp16Detail != null) {
            binding.tcp16DetailText.text = r.tcp16Detail
            binding.tcp16DetailText.visibility = View.VISIBLE
        }
    }

    private fun bindTlsDetail(textView: TextView, detail: String?, trace: List<StageTrace>) {
        val parts = mutableListOf<String>()
        if (!detail.isNullOrBlank()) parts.add("└ $detail")
        val traceStr = formatTrace(trace)
        if (traceStr.isNotBlank()) parts.add("└ $traceStr")
        if (parts.isEmpty()) {
            textView.visibility = View.GONE
            return
        }
        textView.text = parts.joinToString("\n")
        textView.visibility = View.VISIBLE
    }

    private fun formatTrace(trace: List<StageTrace>): String {
        if (trace.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var prev = 0L
        for (e in trace) {
            val delta = (e.elapsedMs - prev).coerceAtLeast(0)
            prev = e.elapsedMs
            parts += "${e.stage}(${delta}ms)"
        }
        return parts.joinToString(" → ")
    }

    private fun setTlsRow(dot: View, text: TextView, status: CheckStatus) {
        val ctx = requireContext()
        val ok =
            status == CheckStatus.Ok || status == CheckStatus.NotBlocked || status == CheckStatus.Redirect
        val isReadTimeout = status == CheckStatus.ReadTimeout
        setDotColor(
            dot,
            if (ok) R.color.success_green else if (isReadTimeout) R.color.warning_orange else R.color.error_red
        )
        text.text = status.label()
        text.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (ok) R.color.success_green else if (isReadTimeout) R.color.warning_orange else R.color.error_red
            )
        )
    }

    private fun setDotColor(dot: View, colorRes: Int) {
        val gd = dot.background as GradientDrawable
        gd.setColor(ContextCompat.getColor(requireContext(), colorRes))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
