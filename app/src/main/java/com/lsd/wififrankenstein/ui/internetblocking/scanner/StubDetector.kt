package com.lsd.wififrankenstein.ui.internetblocking.scanner







internal object StubDetector {

    private val markers = listOf(
        "доступ ограничен",
        "доступ к запрашиваемому ресурсу",
        "решению роскомнадзора",
        "решением суда",
        "заблокирован",
        "blocked by roskomnadzor",
        "blocked by rkn",
        "rkn.gov.ru/org/register",
        "единый реестр",
        "запрещен",
        "запрещён"
    )

    fun looksLikeStub(body: String): Boolean {
        val lower = body.take(2000).lowercase()
        return markers.any { lower.contains(it) }
    }
}
