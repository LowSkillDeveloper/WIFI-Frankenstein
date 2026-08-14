package com.lsd.wififrankenstein.ui.internetblocking.scanner

import android.content.Context
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.internetblocking.model.CheckStatus

object ErrorClassifier {

    fun findCause(exc: Exception, targetClass: String, maxDepth: Int = 10): Exception? {
        var current: Exception? = exc
        for (i in 0 until maxDepth) {
            current ?: break
            if (current.javaClass.simpleName == targetClass || current.javaClass.name == targetClass) {
                return current
            }
            current = current.cause as? Exception
        }
        return null
    }

    fun collectErrorText(exc: Exception, maxDepth: Int = 10): String {
        val parts = mutableListOf<String>()
        var current: Exception? = exc
        for (i in 0 until maxDepth) {
            current ?: break
            parts.add(current.message?.lowercase() ?: "")
            current = current.cause as? Exception
        }
        return parts.joinToString(" | ")
    }

    fun cleanDetail(detail: String): String {
        if (detail.isBlank() || detail == "OK" || detail == "Error") return ""
        var cleaned = detail
            .replace("The operation did not complete", "TLS Aborted")
            .replace("Err None: ", "")
            .replace("Conn failed: ", "")
        cleaned = cleaned.replace("\\s+\\(_*\\s*\$".toRegex(), "")
        cleaned = cleaned.replace("\\s+".toRegex(), " ").trim()
        if (cleaned.matches(Regex("^HTTP [23]\\d\\d$"))) return ""
        return cleaned
    }

    fun classifySslError(
        context: Context,
        error: Exception,
        bytesRead: Long,
        stage: String
    ): Pair<CheckStatus, String> {
        val msg = error.message?.lowercase() ?: ""
        val fullText = collectErrorText(error)


        if ("pop from an empty deque" in fullText || "brokenresourceerror" in fullText) {
            return CheckStatus.TlsRst to context.getString(R.string.ib_ec_ssl_rst)
        }


        if ("wrong version" in msg || "wrong version number" in msg) {
            return CheckStatus.TlsSpoof to context.getString(R.string.ib_ec_tls_spoof_wrong_version)
        }
        if (msg.contains("record overflow") || msg.contains("oversized") ||
            msg.contains("record layer failure") || msg.contains("decode error") ||
            msg.contains("illegal parameter")
        ) {
            return CheckStatus.TlsSpoof to context.getString(R.string.ib_ec_tls_spoof_garbage)
        }


        if ("alert" in msg) {
            if ("unrecognized_name" in msg || "unrecognized name" in msg) {
                return CheckStatus.TlsAlert to context.getString(R.string.ib_ec_sni_block)
            }
            if ("handshake_failure" in msg || "handshake failure" in msg) {
                return CheckStatus.TlsAlert to context.getString(R.string.ib_ec_dpi_alert)
            }
            if ("protocol_version" in msg) {
                return CheckStatus.TlsBlocked to context.getString(R.string.ib_ec_protocol_version_alert)
            }
            return CheckStatus.TlsAlert to context.getString(R.string.ib_ec_fake_tls_alert)
        }


        val eofPatterns = listOf(
            "eof",
            "unexpected eof",
            "eof occurred",
            "operation did not complete",
            "want_read"
        )
        if (eofPatterns.any { it in msg }) {
            if (bytesRead == 0L || stage == STAGE_TLS_HANDSHAKE) {
                return CheckStatus.TlsRst to context.getString(R.string.ib_ec_ssl_rst)
            }
            return CheckStatus.TlsEof to context.getString(R.string.ib_ec_tls_eof)
        }


        if ("certificate" in msg || "unknown ca" in msg || "self-signed" in msg ||
            "certification path" in msg || "pkix" in msg
        ) {
            return CheckStatus.TlsMitm to context.getString(R.string.ib_ec_cert_subst)
        }
        if ("hostname mismatch" in msg) {
            return CheckStatus.TlsMitm to context.getString(R.string.ib_ec_hostname_mismatch)
        }


        if ("protocol version" in msg) {
            return CheckStatus.NoTls13 to context.getString(R.string.ib_ec_no_tls13)
        }


        return CheckStatus.SslError to cleanDetail(error.message ?: context.getString(R.string.ib_ec_unknown_ssl))
    }

    fun classifyConnectError(
        context: Context,
        error: Exception,
        bytesRead: Long,
        stage: String
    ): Pair<CheckStatus, String> {
        val fullText = collectErrorText(error)


        if ("timed out" in fullText || "timeout" in error.message?.lowercase() ?: "") {
            return when (stage) {
                STAGE_TLS_HANDSHAKE -> CheckStatus.TlsDrop to context.getString(R.string.ib_ec_tls_handshake_timeout)
                STAGE_TCP_CONNECT -> CheckStatus.SynDrop to context.getString(R.string.ib_ec_tcp_syn_timeout)
                STAGE_SENDING_DATA -> CheckStatus.SendTimeout to context.getString(R.string.ib_ec_send_timeout)
                STAGE_READING_DATA -> CheckStatus.ReadTimeout to context.getString(R.string.ib_ec_read_timeout)
                else -> CheckStatus.Timeout to context.getString(R.string.ib_ec_timeout_stage, stage)
            }
        }


        if ("gaierror" in fullText || "getaddrinfo failed" in fullText || "name resolution" in fullText) {
            return CheckStatus.DnsFail to context.getString(R.string.ib_ec_dns_fail)
        }


        if ("sslv3_alert" in fullText || "ssl alert" in fullText) {
            if ("handshake_failure" in fullText || "handshake failure" in fullText) {
                return CheckStatus.TlsAlert to context.getString(R.string.ib_ec_handshake_alert)
            }
            if ("unrecognized_name" in fullText) {
                return CheckStatus.TlsAlert to context.getString(R.string.ib_ec_sni_alert)
            }
            return CheckStatus.TlsAlert to context.getString(R.string.ib_ec_tls_alert)
        }


        if ("connection refused" in fullText || "refused" in fullText) {
            return CheckStatus.Refused to context.getString(R.string.ib_ec_refused)
        }


        if ("network is unreachable" in fullText) {
            return CheckStatus.NetUnreachable to context.getString(R.string.ib_ec_net_unreach)
        }


        if ("no route to host" in fullText) {
            return CheckStatus.HostUnreachable to context.getString(R.string.ib_ec_host_unreach)
        }


        if ("connection reset" in fullText || "connection reset by peer" in fullText) {
            if (stage in listOf(STAGE_TLS_HANDSHAKE, STAGE_TLS_CONNECTED)) {
                return CheckStatus.TlsRst to context.getString(R.string.ib_ec_ssl_rst)
            }
            return CheckStatus.TcpRst to context.getString(R.string.ib_ec_tcp_rst)
        }


        if ("connection aborted" in fullText) {
            if (stage in listOf(STAGE_TLS_HANDSHAKE, STAGE_TLS_CONNECTED)) {
                return CheckStatus.TlsAbort to context.getString(R.string.ib_ec_tls_abort)
            }
            return CheckStatus.TcpAbort to context.getString(R.string.ib_ec_tcp_abort)
        }


        return CheckStatus.ConnErr to cleanDetail(error.message ?: context.getString(R.string.ib_ec_unknown_conn))
    }

    fun classifyReadError(
        context: Context,
        error: Exception,
        bytesRead: Long,
        stage: String
    ): Pair<CheckStatus, String> {
        val fullText = collectErrorText(error)


        if ("connection reset" in fullText || "connection reset by peer" in fullText) {
            if (stage in listOf(STAGE_TLS_HANDSHAKE, STAGE_TLS_CONNECTED)) {
                return CheckStatus.TlsRst to context.getString(R.string.ib_ec_ssl_rst)
            }
            return CheckStatus.TcpRst to context.getString(R.string.ib_ec_tcp_rst)
        }


        if ("connection aborted" in fullText) {
            if (stage in listOf(STAGE_TLS_HANDSHAKE, STAGE_TLS_CONNECTED)) {
                return CheckStatus.TlsAbort to context.getString(R.string.ib_ec_tls_abort)
            }
            return CheckStatus.TcpAbort to context.getString(R.string.ib_ec_tcp_abort)
        }


        if ("broken pipe" in fullText) {
            return CheckStatus.TcpRst to context.getString(R.string.ib_ec_broken_pipe)
        }


        if ("peer closed" in fullText || "connection closed" in fullText) {
            return CheckStatus.ProtoErr to context.getString(R.string.ib_ec_closed_early)
        }
        if ("incomplete" in fullText) {
            return CheckStatus.ProtoErr to context.getString(R.string.ib_ec_incomplete)
        }


        return CheckStatus.ReadErr to context.getString(R.string.ib_ec_read_error)
    }

    fun classifyProbeError(error: Exception, iteration: Int): Pair<String, String> {
        val fullText = collectErrorText(error)
        val msg = error.message?.lowercase() ?: ""
        val totalKb = ((iteration + 1) * CHUNK_SIZE / 1024)


        if ("connection reset" in fullText || "connection reset by peer" in fullText) {
            return "TCP RST" to "Connection reset at ${totalKb}KB"
        }


        if ("handshake" in msg || error is javax.net.ssl.SSLException) {
            return "TLS RST" to "TLS error at ${totalKb}KB"
        }


        if ("timed out" in fullText || "timeout" in msg) {
            return "TIMEOUT" to "Timeout at ${totalKb}KB"
        }


        if ("connection refused" in fullText || "refused" in fullText) {
            return "REFUSED" to "Connection refused at ${totalKb}KB"
        }


        if ("name resolution" in fullText || "getaddrinfo failed" in fullText) {
            return "DNS FAIL" to "DNS resolution failed at ${totalKb}KB"
        }


        if ("network is unreachable" in fullText) {
            return "NET UNREACH" to "Network unreachable at ${totalKb}KB"
        }


        return "ERROR" to "${error.javaClass.simpleName} at ${totalKb}KB"
    }

    fun classifyProbeErrorStageAware(
        error: Exception,
        iteration: Int,
        stage: String
    ): Pair<String, String> {
        val fullText = collectErrorText(error)
        val msg = error.message?.lowercase() ?: ""
        val totalKb = ((iteration + 1) * CHUNK_SIZE / 1024)


        if ("failed to connect" in fullText || "connect timeout" in fullText) {
            return "SYN DROP" to "TCP SYN timeout at ${totalKb}KB"
        }


        if ("connection refused" in fullText || "refused" in fullText) {
            return "REFUSED" to "Connection refused at ${totalKb}KB"
        }


        if (error is java.net.ConnectException) {
            return "SYN DROP" to "TCP SYN failed at ${totalKb}KB"
        }


        if ("peer closed" in fullText || "connection closed" in fullText) {
            return "PROTO ERR" to "Closed early at ${totalKb}KB"
        }


        if ("unrecognized_name" in msg || "unrecognized name" in msg) {
            return "TLS ALERT" to "SNI Block at ${totalKb}KB"
        }


        if ("handshake_failure" in msg || "handshake failure" in msg) {
            return "TLS ALERT" to "Handshake failure at ${totalKb}KB"
        }

        if ("handshake" in msg && error is javax.net.ssl.SSLException) {
            return "TLS ALERT" to "Handshake failed at ${totalKb}KB"
        }


        if ("connection reset" in fullText || "connection reset by peer" in fullText) {
            return "TLS RST" to "Connection reset at ${totalKb}KB"
        }


        if ("timed out" in fullText || "timeout" in msg) {
            val stageLabel = when (stage) {
                "tcp_connect" -> "SYN DROP"
                "tls_handshake" -> "TLS DROP"
                "sending_data" -> "SEND TIMEOUT"
                "reading_data" -> "READ TIMEOUT"
                else -> "TIMEOUT"
            }
            return stageLabel to "${stageLabel.replace(" ", "_")} at ${totalKb}KB"
        }


        return "ERROR" to "${error.javaClass.simpleName} at ${totalKb}KB"
    }
}


private const val STAGE_TCP_CONNECT = "tcp_connect"
private const val STAGE_TCP_CONNECTED = "tcp_connected"
private const val STAGE_TLS_HANDSHAKE = "tls_handshake"
private const val STAGE_TLS_CONNECTED = "tls_connected"
private const val STAGE_SENDING_DATA = "sending_data"
private const val STAGE_READING_DATA = "reading_data"
private const val CHUNK_SIZE = 4000
