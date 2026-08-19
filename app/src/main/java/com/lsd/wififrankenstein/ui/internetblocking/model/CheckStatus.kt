package com.lsd.wififrankenstein.ui.internetblocking.model

import android.content.Context
import androidx.annotation.StringRes
import com.lsd.wififrankenstein.R

sealed class CheckStatus {
    object Ok : CheckStatus()
    object Redirect : CheckStatus()
    object Blocked : CheckStatus()
    object DnsSpoof : CheckStatus()
    object FakeIp : CheckStatus()
    object DnsIntercept : CheckStatus()
    object FakeNxdomain : CheckStatus()
    object FakeEmpty : CheckStatus()
    object DohBlocked : CheckStatus()
    object TcpRst : CheckStatus()
    object TlsSpoof : CheckStatus()
    object TlsMitm : CheckStatus()
    object Timeout : CheckStatus()
    object Error : CheckStatus()
    object NotBlocked : CheckStatus()
    object PartiallyBlocked : CheckStatus()
    object Throttled : CheckStatus()
    object IspPage : CheckStatus()
    object LocalIp : CheckStatus()
    object TlsAlert : CheckStatus()
    object TlsBlocked : CheckStatus()
    object NoTls13 : CheckStatus()
    object HostUnreachable : CheckStatus()
    object NetUnreachable : CheckStatus()
    object Refused : CheckStatus()
    object SslError : CheckStatus()
    object TlsRst : CheckStatus()
    object TlsEof : CheckStatus()
    object TlsDrop : CheckStatus()
    object SynDrop : CheckStatus()
    object SendTimeout : CheckStatus()
    object ReadTimeout : CheckStatus()
    object TlsAbort : CheckStatus()
    object TcpAbort : CheckStatus()
    object ProtoErr : CheckStatus()
    object ReadErr : CheckStatus()
    object ConnErr : CheckStatus()
    object DnsFail : CheckStatus()
    object OsErr : CheckStatus()

    fun colorRes(): Int {
        return when (this) {
            Ok -> R.color.success_green
            Redirect -> R.color.success_green
            Blocked -> R.color.error_red
            DnsSpoof -> R.color.error_red
            FakeIp -> R.color.warning_orange
            DnsIntercept -> R.color.error_red
            FakeNxdomain -> R.color.error_red
            FakeEmpty -> R.color.error_red
            DohBlocked -> R.color.error_red
            TcpRst -> R.color.error_red
            TlsSpoof -> R.color.error_red
            TlsMitm -> R.color.error_red
            Timeout -> R.color.warning_orange
            Error -> R.color.error_red
            NotBlocked -> R.color.success_green
            PartiallyBlocked -> R.color.warning_orange
            Throttled -> R.color.warning_orange
            IspPage -> R.color.warning_orange
            LocalIp -> R.color.warning_orange
            TlsAlert -> R.color.error_red
            TlsBlocked -> R.color.error_red
            NoTls13 -> R.color.warning_orange
            HostUnreachable -> R.color.error_red
            NetUnreachable -> R.color.error_red
            Refused -> R.color.error_red
            SslError -> R.color.error_red
            TlsRst -> R.color.error_red
            TlsEof -> R.color.error_red
            TlsDrop -> R.color.warning_orange
            SynDrop -> R.color.warning_orange
            SendTimeout -> R.color.warning_orange
            ReadTimeout -> R.color.warning_orange
            TlsAbort -> R.color.error_red
            TcpAbort -> R.color.error_red
            ProtoErr -> R.color.error_red
            ReadErr -> R.color.error_red
            ConnErr -> R.color.error_red
            DnsFail -> R.color.error_red
            OsErr -> R.color.error_red
        }
    }

    @StringRes
    fun labelRes(): Int = when (this) {
        Ok -> R.string.ib_status_ok
        Redirect -> R.string.ib_status_redirect
        Blocked -> R.string.ib_status_blocked
        DnsSpoof -> R.string.ib_status_dns_spoof
        FakeIp -> R.string.ib_status_fake_ip
        DnsIntercept -> R.string.ib_status_dns_intercept
        FakeNxdomain -> R.string.ib_status_fake_nxdomain
        FakeEmpty -> R.string.ib_status_fake_empty
        DohBlocked -> R.string.ib_status_doh_blocked
        TcpRst -> R.string.ib_status_tcp_rst
        TlsSpoof -> R.string.ib_status_tls_spoof
        TlsMitm -> R.string.ib_status_tls_mitm
        Timeout -> R.string.ib_status_timeout
        Error -> R.string.ib_status_error
        NotBlocked -> R.string.ib_status_not_blocked
        PartiallyBlocked -> R.string.ib_status_partial
        Throttled -> R.string.ib_status_throttled
        IspPage -> R.string.ib_status_isp_page
        LocalIp -> R.string.ib_status_local_ip
        TlsAlert -> R.string.ib_status_tls_alert
        TlsBlocked -> R.string.ib_status_tls_blocked
        NoTls13 -> R.string.ib_status_no_tls13
        HostUnreachable -> R.string.ib_status_host_unreach
        NetUnreachable -> R.string.ib_status_net_unreach
        Refused -> R.string.ib_status_refused
        SslError -> R.string.ib_status_ssl_error
        TlsRst -> R.string.ib_status_tls_rst
        TlsEof -> R.string.ib_status_tls_eof
        TlsDrop -> R.string.ib_status_tls_drop
        SynDrop -> R.string.ib_status_syn_drop
        SendTimeout -> R.string.ib_status_send_timeout
        ReadTimeout -> R.string.ib_status_read_timeout
        TlsAbort -> R.string.ib_status_tls_abort
        TcpAbort -> R.string.ib_status_tcp_abort
        ProtoErr -> R.string.ib_status_proto_err
        ReadErr -> R.string.ib_status_read_err
        ConnErr -> R.string.ib_status_conn_err
        DnsFail -> R.string.ib_status_dns_fail
        OsErr -> R.string.ib_status_os_err
    }

    fun label(context: Context): String = context.getString(labelRes())

    fun label(): String {
        return when (this) {
            Ok -> "OK"
            Redirect -> "REDIR"
            Blocked -> "BLOCKED"
            DnsSpoof -> "DNS SPOOF"
            FakeIp -> "FAKE-IP"
            DnsIntercept -> "DNS INTERCEPT"
            FakeNxdomain -> "FAKE NXDOMAIN"
            FakeEmpty -> "FAKE EMPTY"
            DohBlocked -> "DoH BLOCKED"
            TcpRst -> "TCP RST"
            TlsSpoof -> "TLS SPOOF"
            TlsMitm -> "TLS MITM"
            Timeout -> "TIMEOUT"
            Error -> "ERROR"
            NotBlocked -> "NOT BLOCKED"
            PartiallyBlocked -> "PARTIAL"
            Throttled -> "THROTTLED"
            IspPage -> "ISP PAGE"
            LocalIp -> "LOCAL IP"
            TlsAlert -> "TLS ALERT"
            TlsBlocked -> "TLS BLOCKED"
            NoTls13 -> "NO TLS 1.3"
            HostUnreachable -> "HOST UNREACH"
            NetUnreachable -> "NET UNREACH"
            Refused -> "REFUSED"
            SslError -> "SSL ERROR"
            TlsRst -> "TLS RST"
            TlsEof -> "TLS EOF"
            TlsDrop -> "TLS DROP"
            SynDrop -> "SYN DROP"
            SendTimeout -> "SEND TIMEOUT"
            ReadTimeout -> "READ TIMEOUT"
            TlsAbort -> "TLS ABORT"
            TcpAbort -> "TCP ABORT"
            ProtoErr -> "PROTO ERR"
            ReadErr -> "READ ERR"
            ConnErr -> "CONN ERR"
            DnsFail -> "DNS FAIL"
            OsErr -> "OS ERR"
        }
    }
}
