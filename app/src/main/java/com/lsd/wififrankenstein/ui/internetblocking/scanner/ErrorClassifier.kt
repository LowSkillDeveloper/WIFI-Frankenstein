package com.lsd.wififrankenstein.ui.internetblocking.scanner

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
        error: Exception,
        bytesRead: Long,
        stage: String
    ): Pair<CheckStatus, String> {
        val msg = error.message?.lowercase() ?: ""
        val fullText = collectErrorText(error)


        if ("pop from an empty deque" in fullText || "brokenresourceerror" in fullText) {
            return CheckStatus.TlsRst to "Активный сброс (TCP RST)"
        }


        if ("wrong version" in msg || "wrong version number" in msg) {
            return CheckStatus.TlsSpoof to "Подмена ответа (Wrong Version)"
        }
        if (msg.contains("record overflow") || msg.contains("oversized") ||
            msg.contains("record layer failure") || msg.contains("decode error") ||
            msg.contains("illegal parameter")
        ) {
            return CheckStatus.TlsSpoof to "Подмена ответа (Garbage Data)"
        }


        if ("alert" in msg) {
            if ("unrecognized_name" in msg || "unrecognized name" in msg) {
                return CheckStatus.TlsAlert to "SNI Block (Unrecognized Name)"
            }
            if ("handshake_failure" in msg || "handshake failure" in msg) {
                return CheckStatus.TlsAlert to "DPI Alert (Handshake Failure)"
            }
            if ("protocol_version" in msg) {
                return CheckStatus.TlsBlocked to "Protocol Version Alert"
            }
            return CheckStatus.TlsAlert to "Поддельный TLS Alert"
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
                return CheckStatus.TlsRst to "Активный сброс (TCP RST)"
            }
            return CheckStatus.TlsEof to "Обрыв при передаче (EOF)"
        }


        if ("certificate" in msg || "unknown ca" in msg || "self-signed" in msg ||
            "certification path" in msg || "pkix" in msg
        ) {
            return CheckStatus.TlsMitm to "Подмена сертификата"
        }
        if ("hostname mismatch" in msg) {
            return CheckStatus.TlsMitm to "Hostname mismatch"
        }


        if ("protocol version" in msg) {
            return CheckStatus.NoTls13 to "Server has no TLS 1.3"
        }


        return CheckStatus.SslError to cleanDetail(error.message ?: "Unknown SSL error")
    }

    fun classifyConnectError(
        error: Exception,
        bytesRead: Long,
        stage: String
    ): Pair<CheckStatus, String> {
        val fullText = collectErrorText(error)


        if ("timed out" in fullText || "timeout" in error.message?.lowercase() ?: "") {
            return when (stage) {
                STAGE_TLS_HANDSHAKE -> CheckStatus.TlsDrop to "TLS Handshake timeout"
                STAGE_TCP_CONNECT -> CheckStatus.SynDrop to "TCP SYN timeout"
                STAGE_SENDING_DATA -> CheckStatus.SendTimeout to "Таймаут отправки данных"
                STAGE_READING_DATA -> CheckStatus.ReadTimeout to "Таймаут чтения данных"
                else -> CheckStatus.Timeout to "Timeout ($stage)"
            }
        }


        if ("gaierror" in fullText || "getaddrinfo failed" in fullText || "name resolution" in fullText) {
            return CheckStatus.DnsFail to "Ошибка DNS"
        }


        if ("sslv3_alert" in fullText || "ssl alert" in fullText) {
            if ("handshake_failure" in fullText || "handshake failure" in fullText) {
                return CheckStatus.TlsAlert to "Handshake alert"
            }
            if ("unrecognized_name" in fullText) {
                return CheckStatus.TlsAlert to "SNI alert"
            }
            return CheckStatus.TlsAlert to "TLS alert"
        }


        if ("connection refused" in fullText || "refused" in fullText) {
            return CheckStatus.Refused to "TCP соединение отклонено"
        }


        if ("network is unreachable" in fullText) {
            return CheckStatus.NetUnreachable to "Нет маршрута (ICMP unreach)"
        }


        if ("no route to host" in fullText) {
            return CheckStatus.HostUnreachable to "Нет маршрута до хоста"
        }


        if ("connection reset" in fullText || "connection reset by peer" in fullText) {
            if (stage in listOf(STAGE_TLS_HANDSHAKE, STAGE_TLS_CONNECTED)) {
                return CheckStatus.TlsRst to "Активный сброс (TCP RST)"
            }
            return CheckStatus.TcpRst to "TCP соединение сброшено"
        }


        if ("connection aborted" in fullText) {
            if (stage in listOf(STAGE_TLS_HANDSHAKE, STAGE_TLS_CONNECTED)) {
                return CheckStatus.TlsAbort to "Соединение прервано (Abort)"
            }
            return CheckStatus.TcpAbort to "TCP соединение прервано"
        }


        return CheckStatus.ConnErr to cleanDetail(error.message ?: "Unknown connection error")
    }

    fun classifyReadError(
        error: Exception,
        bytesRead: Long,
        stage: String
    ): Pair<CheckStatus, String> {
        val fullText = collectErrorText(error)


        if ("connection reset" in fullText || "connection reset by peer" in fullText) {
            if (stage in listOf(STAGE_TLS_HANDSHAKE, STAGE_TLS_CONNECTED)) {
                return CheckStatus.TlsRst to "Активный сброс (TCP RST)"
            }
            return CheckStatus.TcpRst to "TCP соединение сброшено"
        }


        if ("connection aborted" in fullText) {
            if (stage in listOf(STAGE_TLS_HANDSHAKE, STAGE_TLS_CONNECTED)) {
                return CheckStatus.TlsAbort to "Соединение прервано (Abort)"
            }
            return CheckStatus.TcpAbort to "TCP соединение прервано"
        }


        if ("broken pipe" in fullText) {
            return CheckStatus.TcpRst to "Broken pipe"
        }


        if ("peer closed" in fullText || "connection closed" in fullText) {
            return CheckStatus.ProtoErr to "Closed early"
        }
        if ("incomplete" in fullText) {
            return CheckStatus.ProtoErr to "Incomplete response"
        }


        return CheckStatus.ReadErr to "Read error"
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
