package com.lsd.wififrankenstein.ui.api3wifi

sealed class API3WiFiRequest(val methodName: String) {
    class ApiKeys(val login: String, val password: String, val genRead: Boolean = false, val genWrite: Boolean = false) : API3WiFiRequest("apikeys")

    class ApiQuery(
        val key: String,
        val bssidList: List<String>? = null,
        val essidList: List<String>? = null,
        val sens: Boolean = false
    ) : API3WiFiRequest("apiquery")

    class ApiWps(val key: String, val bssidList: List<String>) : API3WiFiRequest("apiwps")

    class ApiDev(
        val key: String,
        val bssidList: List<String>,
        val nocli: Boolean = true
    ) : API3WiFiRequest("apidev")

    class ApiRanges(
        val key: String,
        val lat: Float,
        val lon: Float,
        val rad: Float
    ) : API3WiFiRequest("apiranges")
}