package com.lsd.wififrankenstein.ui.api3wifi

sealed class API3WiFiRequest(val methodName: String) {
    class ApiKeys(
        val login: String,
        val password: String,
        val genRead: Boolean = false,
        val genWrite: Boolean = false
    ) : API3WiFiRequest("apikeys")

    class ApiQuery(
        val key: String,
        val bssidList: List<String>? = null,
        val essidList: List<String>? = null,
        val sens: Boolean = false,
        val name: String? = null,
        val auth: String? = null
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

    class TrpcGetPoint(
        val id: Int,
        val bssid: String? = null
    ) : API3WiFiRequest("getAccessPointDetails")

    class TrpcSearchNetworks(
        val query: String,
        val type: String
    ) : API3WiFiRequest("searchNetworks")
}