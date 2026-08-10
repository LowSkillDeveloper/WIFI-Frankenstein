package com.lsd.wififrankenstein.ui.ipranges

import com.lsd.wififrankenstein.util.Log

class WhoisParser {

    fun parseWhoisResponse(whoisText: String, rir: RIR): IpRange? {
        return try {
            when (rir) {
                RIR.RIPE, RIR.APNIC, RIR.AFRINIC -> parseRipeStyleWhois(whoisText)
                RIR.ARIN -> parseArinWhois(whoisText)
                RIR.LACNIC -> parseLacnicWhois(whoisText)
            }
        } catch (e: Exception) {
            Log.e("WhoisParser", "Failed to parse WHOIS response for $rir", e)
            null
        }
    }

    private fun parseRipeStyleWhois(whoisText: String): IpRange? {
        val inetnum = getWhoisField(whoisText, "inetnum")
        if (inetnum.isEmpty()) return null

        val parts = inetnum.split(" - ")
        if (parts.size != 2) return null

        val startIP = ipToLong(parts[0].trim())
        val endIP = ipToLong(parts[1].trim())

        val netname = getWhoisField(whoisText, "netname")
        val descr = getWhoisFieldArray(whoisText, "descr").joinToString(" | ")
        val country = getWhoisField(whoisText, "country").uppercase()

        return IpRange(startIP, endIP, netname, descr, country)
    }

    private fun parseArinWhois(whoisText: String): IpRange? {
        val netRange = getWhoisField(whoisText, "NetRange")
        if (netRange.isEmpty()) return null

        val parts = netRange.split(" - ")
        if (parts.size != 2) return null

        val startIP = ipToLong(parts[0].trim())
        val endIP = ipToLong(parts[1].trim())

        val netname = getWhoisField(whoisText, "NetName")
        val orgName = getWhoisField(whoisText, "OrgName")
        val custName = getWhoisField(whoisText, "CustName")
        val descr = if (orgName.isNotEmpty()) orgName else custName
        val country = getWhoisField(whoisText, "Country").uppercase()

        return IpRange(startIP, endIP, netname, descr, country)
    }

    private fun parseLacnicWhois(whoisText: String): IpRange? {
        val inetnum = getWhoisField(whoisText, "inetnum")
        if (inetnum.isEmpty()) return null

        val cidrRange = cidrToRange(inetnum)

        val netname = getWhoisField(whoisText, "netname")
        val owner = getWhoisField(whoisText, "owner")
        val country = getWhoisField(whoisText, "country").uppercase()

        return IpRange(cidrRange.first, cidrRange.second, netname, owner, country)
    }

    private fun getWhoisField(whoisText: String, fieldName: String): String {
        val regex = "\\n$fieldName:\\s*(.+)".toRegex()
        return regex.find(whoisText)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun getWhoisFieldArray(whoisText: String, fieldName: String): List<String> {
        val regex = "\\n$fieldName:\\s*(.+)".toRegex()
        return regex.findAll(whoisText).map { it.groupValues[1].trim() }.toList()
    }

    private fun cidrToRange(cidr: String): Pair<Long, Long> {
        val parts = cidr.split("/")
        val ip = parts[0].trim()
        val mask = if (parts.size > 1) parts[1].toInt() else 32

        val baseIp = ipToLong(ip)
        val hostBits = 32 - mask
        val networkMask = (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
        val hostMask = (1L shl hostBits) - 1

        val startIP = baseIp and networkMask
        val endIP = startIP or hostMask

        return Pair(startIP, endIP)
    }

    private fun ipToLong(ip: String): Long {
        val parts = ip.split(".")
        return ((parts[0].toLong() shl 24) +
                (parts[1].toLong() shl 16) +
                (parts[2].toLong() shl 8) +
                parts[3].toLong()) and 0xFFFFFFFFL
    }
}