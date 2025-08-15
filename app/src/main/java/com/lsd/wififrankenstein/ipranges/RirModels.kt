package com.lsd.wififrankenstein.ui.ipranges

data class IpRange(
    val startIP: Long,
    val endIP: Long,
    val netname: String,
    val description: String,
    val country: String
)

enum class RIR(val id: Int, val whoisServer: String, val rdapUrl: String) {
    RIPE(1, "whois.ripe.net", "http://rdap.db.ripe.net/"),
    APNIC(2, "whois.apnic.net", "http://rdap.apnic.net/"),
    ARIN(3, "whois.arin.net", "http://rdap.arin.net/registry/"),
    AFRINIC(4, "whois.afrinic.net", "http://rdap.afrinic.net/rdap/"),
    LACNIC(5, "whois.lacnic.net", "http://rdap.lacnic.net/rdap/")
}

data class RdapResponse(
    val port43: String?,
    val startAddress: String?,
    val endAddress: String?,
    val name: String?,
    val country: String?,
    val description: List<String>?
)