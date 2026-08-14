package com.lsd.wififrankenstein.ui.internetblocking.scanner

import android.content.Context
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus









data class BlockDiagnosisResult(
    val blockStage: String,
    val blockMechanism: String,
    val conclusion: String,
    val confidence: String
)




internal object BlockDiagnosis {

    private val dnsBlockStatuses = setOf(
        CheckStatus.DnsSpoof,
        CheckStatus.DnsIntercept,
        CheckStatus.FakeIp,
        CheckStatus.FakeNxdomain,
        CheckStatus.FakeEmpty,
        CheckStatus.DohBlocked
    )

    private val okStatuses = setOf(
        CheckStatus.Ok,
        CheckStatus.NotBlocked,
        CheckStatus.Redirect
    )











    fun diagnose(
        context: Context,
        dnsStatus: CheckStatus,
        tls13Status: CheckStatus,
        tls12Status: CheckStatus,
        httpStatus: CheckStatus,
        tcpReachable: Boolean,
        port80Reachable: Boolean,
        baselineReachable: Boolean,
        sniDifferential: SniBlockVerdict,
        tcp443Refused: Boolean = false,
        httpStub: Boolean = false
    ): BlockDiagnosisResult {
        if (!baselineReachable) {
            return BlockDiagnosisResult(
                blockStage = context.getString(R.string.ib_bd_stage_network),
                blockMechanism = context.getString(R.string.ib_bd_mech_base_hosts_down),
                conclusion = context.getString(R.string.ib_bd_conc_base_hosts_down),
                confidence = context.getString(R.string.ib_bd_conf_medium)
            )
        }

        if (dnsStatus in dnsBlockStatuses) {
            return BlockDiagnosisResult(
                blockStage = context.getString(R.string.ib_bd_stage_dns),
                blockMechanism = dnsMechanism(context, dnsStatus),
                conclusion = context.getString(R.string.ib_bd_conc_dns_spoofed),
                confidence = context.getString(R.string.ib_bd_conf_high)
            )
        }

        if (dnsStatus == CheckStatus.Error) {
            return BlockDiagnosisResult(
                blockStage = context.getString(R.string.ib_bd_stage_dns),
                blockMechanism = context.getString(R.string.ib_bd_mech_domain_not_resolved),
                conclusion = context.getString(R.string.ib_bd_conc_domain_not_resolved),
                confidence = context.getString(R.string.ib_bd_conf_low)
            )
        }

        if (!tcpReachable) {
            val (mechanism, confidence) = when {
                tcp443Refused -> context.getString(R.string.ib_bd_mech_tcp_refused) to context.getString(R.string.ib_bd_conf_medium)
                port80Reachable -> context.getString(R.string.ib_bd_mech_port_block_443) to context.getString(R.string.ib_bd_conf_high)
                else -> context.getString(R.string.ib_bd_mech_syn_drop) to context.getString(R.string.ib_bd_conf_high)
            }
            return BlockDiagnosisResult(
                blockStage = context.getString(R.string.ib_bd_stage_tcp_ip),
                blockMechanism = mechanism,
                conclusion = context.getString(R.string.ib_bd_conc_tcp_blocked),
                confidence = confidence
            )
        }

        val readTimeout = listOf(tls13Status, tls12Status, httpStatus).any {
            it == CheckStatus.ReadTimeout
        }
        if (readTimeout) {
            return BlockDiagnosisResult(
                blockStage = context.getString(R.string.ib_bd_stage_tcp_throttling),
                blockMechanism = context.getString(R.string.ib_bd_mech_read_drop),
                conclusion = context.getString(R.string.ib_bd_conc_tcp_throttling),
                confidence = context.getString(R.string.ib_bd_conf_high)
            )
        }

        val tlsOk = okStatuses.contains(tls13Status) && okStatuses.contains(tls12Status)
        if (!tlsOk) {
            return when (sniDifferential) {
                SniBlockVerdict.SNI_BLOCKED -> BlockDiagnosisResult(
                    blockStage = context.getString(R.string.ib_bd_stage_tls_dpi),
                    blockMechanism = context.getString(R.string.ib_bd_mech_sni_block),
                    conclusion = context.getString(R.string.ib_bd_conc_sni_block),
                    confidence = context.getString(R.string.ib_bd_conf_medium)
                )

                SniBlockVerdict.IP_BLOCKED -> BlockDiagnosisResult(
                    blockStage = context.getString(R.string.ib_bd_stage_tls),
                    blockMechanism = tlsMechanism(context, tls13Status, tls12Status),
                    conclusion = context.getString(R.string.ib_bd_conc_tls_ip_blocked),
                    confidence = context.getString(R.string.ib_bd_conf_medium)
                )

                SniBlockVerdict.INCONCLUSIVE -> BlockDiagnosisResult(
                    blockStage = context.getString(R.string.ib_bd_stage_tls),
                    blockMechanism = tlsMechanism(context, tls13Status, tls12Status),
                    conclusion = context.getString(R.string.ib_bd_conc_tls_inconclusive),
                    confidence = context.getString(R.string.ib_bd_conf_low)
                )
            }
        }

        if (httpStub) {
            return BlockDiagnosisResult(
                blockStage = context.getString(R.string.ib_bd_stage_http),
                blockMechanism = context.getString(R.string.ib_bd_mech_stub_page),
                conclusion = context.getString(R.string.ib_bd_conc_stub_page),
                confidence = context.getString(R.string.ib_bd_conf_high)
            )
        }

        if (httpStatus == CheckStatus.Blocked) {
            return BlockDiagnosisResult(
                blockStage = context.getString(R.string.ib_bd_stage_http),
                blockMechanism = context.getString(R.string.ib_bd_mech_http451),
                conclusion = context.getString(R.string.ib_bd_conc_http451),
                confidence = context.getString(R.string.ib_bd_conf_high)
            )
        }

        if (httpStatus !in okStatuses) {
            return BlockDiagnosisResult(
                blockStage = context.getString(R.string.ib_bd_stage_http),
                blockMechanism = httpMechanism(context, httpStatus),
                conclusion = context.getString(R.string.ib_bd_conc_http_failed, httpStatus.label(context)),
                confidence = context.getString(R.string.ib_bd_conf_medium)
            )
        }

        return BlockDiagnosisResult(
            blockStage = "—",
            blockMechanism = "—",
            conclusion = context.getString(R.string.ib_bd_conc_no_block),
            confidence = "—"
        )
    }

    private fun dnsMechanism(context: Context, status: CheckStatus): String = when (status) {
        CheckStatus.DnsSpoof -> context.getString(R.string.ib_bd_dns_spoof)
        CheckStatus.DnsIntercept -> context.getString(R.string.ib_bd_dns_intercept)
        CheckStatus.FakeIp -> context.getString(R.string.ib_bd_dns_fake_ip)
        CheckStatus.FakeNxdomain -> context.getString(R.string.ib_bd_dns_fake_nxdomain)
        CheckStatus.FakeEmpty -> context.getString(R.string.ib_bd_dns_fake_empty)
        CheckStatus.DohBlocked -> context.getString(R.string.ib_bd_dns_doh_blocked)
        else -> status.label(context)
    }

    private fun tlsMechanism(context: Context, s1: CheckStatus, s2: CheckStatus): String {
        val bad = listOf(s1, s2).filterNot { it in okStatuses }
        return when (bad.firstOrNull()) {
            CheckStatus.TlsRst -> context.getString(R.string.ib_bd_tls_rst)
            CheckStatus.TlsDrop -> context.getString(R.string.ib_bd_tls_drop)
            CheckStatus.TlsAlert -> context.getString(R.string.ib_bd_tls_alert)
            CheckStatus.TlsMitm -> context.getString(R.string.ib_bd_tls_mitm)
            CheckStatus.TlsSpoof -> context.getString(R.string.ib_bd_tls_spoof)
            CheckStatus.TlsEof -> context.getString(R.string.ib_bd_tls_eof)
            CheckStatus.SynDrop -> context.getString(R.string.ib_bd_tls_syn_drop)
            CheckStatus.NoTls13 -> context.getString(R.string.ib_bd_tls_no_tls13)
            else -> bad.firstOrNull()?.label(context) ?: context.getString(R.string.ib_bd_tls_fail)
        }
    }

    private fun httpMechanism(context: Context, status: CheckStatus): String = when (status) {
        CheckStatus.Timeout -> context.getString(R.string.ib_bd_http_timeout)
        CheckStatus.SynDrop -> context.getString(R.string.ib_bd_http_syn_timeout)
        CheckStatus.SendTimeout -> context.getString(R.string.ib_bd_http_send_timeout)
        CheckStatus.ReadTimeout -> context.getString(R.string.ib_bd_http_read_timeout)
        CheckStatus.TcpRst -> context.getString(R.string.ib_bd_http_tcp_rst)
        CheckStatus.Refused -> context.getString(R.string.ib_bd_http_refused)
        CheckStatus.Error -> context.getString(R.string.ib_bd_http_error)
        else -> context.getString(R.string.ib_bd_http_label, status.label(context))
    }
}
