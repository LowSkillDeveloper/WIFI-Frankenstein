package com.lsd.wififrankenstein.ui.internetblocking.model

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
            Ok -> com.lsd.wififrankenstein.R.color.success_green
            Redirect -> com.lsd.wififrankenstein.R.color.success_green
            Blocked -> com.lsd.wififrankenstein.R.color.error_red
            DnsSpoof -> com.lsd.wififrankenstein.R.color.error_red
            FakeIp -> com.lsd.wififrankenstein.R.color.warning_orange
            DnsIntercept -> com.lsd.wififrankenstein.R.color.error_red
            FakeNxdomain -> com.lsd.wififrankenstein.R.color.error_red
            FakeEmpty -> com.lsd.wififrankenstein.R.color.error_red
            DohBlocked -> com.lsd.wififrankenstein.R.color.error_red
            TcpRst -> com.lsd.wififrankenstein.R.color.error_red
            TlsSpoof -> com.lsd.wififrankenstein.R.color.error_red
            TlsMitm -> com.lsd.wififrankenstein.R.color.error_red
            Timeout -> com.lsd.wififrankenstein.R.color.warning_orange
            Error -> com.lsd.wififrankenstein.R.color.error_red
            NotBlocked -> com.lsd.wififrankenstein.R.color.success_green
            PartiallyBlocked -> com.lsd.wififrankenstein.R.color.warning_orange
            Throttled -> com.lsd.wififrankenstein.R.color.warning_orange
            IspPage -> com.lsd.wififrankenstein.R.color.warning_orange
            LocalIp -> com.lsd.wififrankenstein.R.color.warning_orange
            TlsAlert -> com.lsd.wififrankenstein.R.color.error_red
            TlsBlocked -> com.lsd.wififrankenstein.R.color.error_red
            NoTls13 -> com.lsd.wififrankenstein.R.color.warning_orange
            HostUnreachable -> com.lsd.wififrankenstein.R.color.error_red
            NetUnreachable -> com.lsd.wififrankenstein.R.color.error_red
            Refused -> com.lsd.wififrankenstein.R.color.error_red
            SslError -> com.lsd.wififrankenstein.R.color.error_red
            TlsRst -> com.lsd.wififrankenstein.R.color.error_red
            TlsEof -> com.lsd.wififrankenstein.R.color.error_red
            TlsDrop -> com.lsd.wififrankenstein.R.color.warning_orange
            SynDrop -> com.lsd.wififrankenstein.R.color.warning_orange
            SendTimeout -> com.lsd.wififrankenstein.R.color.warning_orange
            ReadTimeout -> com.lsd.wififrankenstein.R.color.warning_orange
            TlsAbort -> com.lsd.wififrankenstein.R.color.error_red
            TcpAbort -> com.lsd.wififrankenstein.R.color.error_red
            ProtoErr -> com.lsd.wififrankenstein.R.color.error_red
            ReadErr -> com.lsd.wififrankenstein.R.color.error_red
            ConnErr -> com.lsd.wififrankenstein.R.color.error_red
            DnsFail -> com.lsd.wififrankenstein.R.color.error_red
            OsErr -> com.lsd.wififrankenstein.R.color.error_red
        }
    }

    fun label(): String {
        return when (this) {
            Ok -> "OK"
            Redirect -> "REDIR"
            Blocked -> "BLOCKED"
            DnsSpoof -> "DNS ПОДМЕНА"
            FakeIp -> "FAKE-IP"
            DnsIntercept -> "DNS ПЕРЕХВАТ"
            FakeNxdomain -> "FAKE NXDOMAIN"
            FakeEmpty -> "FAKE EMPTY"
            DohBlocked -> "DoH ЗАБЛОКИРОВАН"
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
