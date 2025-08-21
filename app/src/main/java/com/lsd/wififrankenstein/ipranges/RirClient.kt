package com.lsd.wififrankenstein.ui.ipranges

import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

class RirClient {

    suspend fun fetchRdap(url: String, ip: String): RdapResponse? = withContext(Dispatchers.IO) {
        try {
            val connection = URL("${url}ip/$ip").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json, application/rdap+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseRdapResponse(response)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("RirClient", "RDAP request failed for $ip", e)
            null
        }
    }

    suspend fun fetchWhois(server: String, request: String): String? = withContext(Dispatchers.IO) {
        try {
            Socket(server, 43).use { socket ->
                socket.getOutputStream().write("$request\r\n".toByteArray())
                socket.getInputStream().bufferedReader().use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            Log.e("RirClient", "WHOIS request failed to $server", e)
            null
        }
    }

    private fun parseRdapResponse(response: String): RdapResponse? {
        return try {
            val json = JSONObject(response)
            RdapResponse(
                port43 = json.optString("port43", null),
                startAddress = json.optString("startAddress", null),
                endAddress = json.optString("endAddress", null),
                name = json.optString("name", null),
                country = json.optString("country", null),
                description = null
            )
        } catch (e: Exception) {
            Log.e("RirClient", "Failed to parse RDAP response", e)
            null
        }
    }

    fun getRirForIp(ip: String): RIR? {
        return try {
            val parts = ip.split(".")
            val firstOctet = parts[0].toInt()

            when (firstOctet) {
                in 1..2, in 5..6, in 37..37, in 46..46, in 62..62,
                in 77..95, in 109..109, in 141..141, in 151..151,
                in 176..176, in 185..185, in 188..188, in 193..194,
                in 212..213, in 217..217 -> RIR.RIPE

                in 14..15, in 27..27, in 36..36, in 39..39,
                in 42..43, in 49..49, in 58..58, in 59..61,
                in 101..101, in 103..103, in 106..106, in 110..111,
                in 112..126, in 133..133, in 150..150, in 153..153,
                in 163..163, in 171..171, in 175..175, in 180..180,
                in 182..183, in 202..203, in 210..211, in 218..222 -> RIR.APNIC

                in 3..3, in 4..4, in 7..11, in 12..12, in 13..13,
                in 16..16, in 17..17, in 18..35, in 38..38,
                in 40..41, in 44..45, in 47..48, in 50..57,
                in 63..76, in 96..100, in 104..105, in 107..108,
                in 128..132, in 134..140, in 142..149, in 152..155,
                in 156..162, in 164..170, in 172..174, in 184..184,
                in 192..192, in 198..199, in 204..209, in 214..216 -> RIR.ARIN

                in 102..102, in 154..154, in 196..197 -> RIR.AFRINIC

                in 177..179, in 181..181, in 186..187,
                in 189..191, in 200..201 -> RIR.LACNIC

                else -> RIR.ARIN // Default fallback
            }
        } catch (e: Exception) {
            Log.e("RirClient", "Failed to determine RIR for IP: $ip", e)
            null
        }
    }
}