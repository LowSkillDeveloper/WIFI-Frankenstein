package com.lsd.wififrankenstein.ui.internetblocking.scanner

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
                blockStage = "Сеть",
                blockMechanism = "Базовые хосты (1.1.1.1, 8.8.8.8) недоступны",
                conclusion = "Интернет-соединение нарушено целиком (базовые хосты не отвечают). " +
                        "Проверьте Wi-Fi/мобильные данные.",
                confidence = "средняя уверенность"
            )
        }

        if (dnsStatus in dnsBlockStatuses) {
            return BlockDiagnosisResult(
                blockStage = "DNS",
                blockMechanism = dnsMechanism(dnsStatus),
                conclusion = "DNS отвечает подменёнными данными — блокировка на уровне DNS. " +
                        "Полный анализ — во вкладке DNS.",
                confidence = "высокая уверенность"
            )
        }

        if (dnsStatus == CheckStatus.Error) {
            return BlockDiagnosisResult(
                blockStage = "DNS",
                blockMechanism = "Домен не разрешился (UDP и DoH пусто)",
                conclusion = "Не удалось разрешить домен. Проверьте, существует ли домен, " +
                        "и не блокируется ли сам DNS.",
                confidence = "низкая уверенность"
            )
        }

        if (!tcpReachable) {
            val (mechanism, confidence) = when {
                tcp443Refused -> "TCP-соединение активно отклонено (REFUSED)" to "средняя уверенность"
                port80Reachable -> "TCP:443 не отвечает, но порт 80 открыт — порт-блокировка / DPI на 443" to "высокая уверенность"
                else -> "SYN-пакеты молча роняются (blackhole / silent drop, без RST)" to "высокая уверенность"
            }
            return BlockDiagnosisResult(
                blockStage = "TCP/IP",
                blockMechanism = mechanism,
                conclusion = "DNS чистый, но TCP-соединение к адресам домена не устанавливается — " +
                        "блок на IP/TCP-уровне (не DNS и не SNI).",
                confidence = confidence
            )
        }

        val readTimeout = listOf(tls13Status, tls12Status, httpStatus).any {
            it == CheckStatus.ReadTimeout
        }
        if (readTimeout) {
            return BlockDiagnosisResult(
                blockStage = "TCP (троттлинг)",
                blockMechanism = "Обрыв чтения после небольшого объёма данных (TCP 16-20KB drop)",
                conclusion = "Соединение устанавливается, но данные режутся после ~16-20KB — " +
                        "признак DPI-троттлинга. Проверьте во вкладке TCP.",
                confidence = "высокая уверенность"
            )
        }

        val tlsOk = okStatuses.contains(tls13Status) && okStatuses.contains(tls12Status)
        if (!tlsOk) {
            return when (sniDifferential) {
                SniBlockVerdict.SNI_BLOCKED -> BlockDiagnosisResult(
                    blockStage = "TLS/DPI",
                    blockMechanism = "SNI-based блок: TLS с SNI домена падает, с чужим SNI работает",
                    conclusion = "TCP открывается, но TLS-хендшейк блокируется по SNI — активный " +
                            "SNI-фильтр на пути (ISP/TSPU). Рабочие SNI можно найти во вкладке SNI.",
                    confidence = "средняя уверенность"
                )

                SniBlockVerdict.IP_BLOCKED -> BlockDiagnosisResult(
                    blockStage = "TLS",
                    blockMechanism = tlsMechanism(tls13Status, tls12Status),
                    conclusion = "TCP открывается, но TLS-хендшейк не проходит даже с чужим SNI — " +
                            "блокировка на уровне TLS/IP.",
                    confidence = "средняя уверенность"
                )

                SniBlockVerdict.INCONCLUSIVE -> BlockDiagnosisResult(
                    blockStage = "TLS",
                    blockMechanism = tlsMechanism(tls13Status, tls12Status),
                    conclusion = "TCP открывается, но TLS-хендшейк не проходит; SNI-дифференциал " +
                            "не дал однозначного результата.",
                    confidence = "низкая уверенность"
                )
            }
        }

        if (httpStub) {
            return BlockDiagnosisResult(
                blockStage = "HTTP",
                blockMechanism = "Страница-заглушка провайдера в теле ответа",
                conclusion = "Сервер отвечает, но тело ответа — заглушка провайдера (блок на уровне контента).",
                confidence = "высокая уверенность"
            )
        }

        if (httpStatus == CheckStatus.Blocked) {
            return BlockDiagnosisResult(
                blockStage = "HTTP",
                blockMechanism = "HTTP 451 / cross-domain redirect",
                conclusion = "TLS проходит, но HTTP-ответ блокируется (451/редирект) на уровне контента.",
                confidence = "высокая уверенность"
            )
        }

        if (httpStatus !in okStatuses) {
            return BlockDiagnosisResult(
                blockStage = "HTTP",
                blockMechanism = httpMechanism(httpStatus),
                conclusion = "TLS проходит, но HTTP-запрос не удался (${httpStatus.label()}).",
                confidence = "средняя уверенность"
            )
        }

        return BlockDiagnosisResult(
            blockStage = "—",
            blockMechanism = "—",
            conclusion = "Домен доступен: DNS, TCP и TLS проходят. Блокировка не обнаружена.",
            confidence = "—"
        )
    }

    private fun dnsMechanism(status: CheckStatus): String = when (status) {
        CheckStatus.DnsSpoof -> "DNS-подмена: UDP и DoH вернули разные IP"
        CheckStatus.DnsIntercept -> "DNS-перехват: UDP не ответил, DoH вернул IP"
        CheckStatus.FakeIp -> "Fake-IP (198.18.0.0/15) в DNS-ответе"
        CheckStatus.FakeNxdomain -> "Fake NXDOMAIN в DNS-ответе"
        CheckStatus.FakeEmpty -> "Fake empty DNS-ответ"
        CheckStatus.DohBlocked -> "DoH-серверы заблокированы"
        else -> status.label()
    }

    private fun tlsMechanism(s1: CheckStatus, s2: CheckStatus): String {
        val bad = listOf(s1, s2).filterNot { it in okStatuses }
        return when (bad.firstOrNull()) {
            CheckStatus.TlsRst -> "Активный сброс TLS (TCP RST на handshake)"
            CheckStatus.TlsDrop -> "TLS handshake зависает (пакеты роняются)"
            CheckStatus.TlsAlert -> "TLS alert от DPI"
            CheckStatus.TlsMitm -> "Подмена сертификата (MITM)"
            CheckStatus.TlsSpoof -> "Подмена TLS-ответа (garbage data)"
            CheckStatus.TlsEof -> "Обрыв TLS (EOF)"
            CheckStatus.SynDrop -> "SYN не доходит до сервера"
            CheckStatus.NoTls13 -> "Сервер не поддерживает TLS 1.3"
            else -> bad.firstOrNull()?.label() ?: "TLS-сбой"
        }
    }

    private fun httpMechanism(status: CheckStatus): String = when (status) {
        CheckStatus.Timeout -> "HTTP таймаут (порт 80 фильтруется)"
        CheckStatus.SynDrop -> "HTTP SYN timeout (порт 80)"
        CheckStatus.SendTimeout -> "Таймаут отправки HTTP"
        CheckStatus.ReadTimeout -> "Таймаут чтения HTTP"
        CheckStatus.TcpRst -> "TCP RST на HTTP"
        CheckStatus.Refused -> "HTTP соединение отклонено"
        CheckStatus.Error -> "Ошибка HTTP-запроса"
        else -> "HTTP: ${status.label()}"
    }
}
