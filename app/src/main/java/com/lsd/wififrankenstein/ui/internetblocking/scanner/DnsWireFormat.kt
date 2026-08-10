package com.lsd.wififrankenstein.ui.internetblocking.scanner







internal object DnsWireFormat {





    fun buildDnsQuery(txId: Short, domain: String): ByteArray {
        val result = java.io.ByteArrayOutputStream(64)
        result.write(txId.toInt() shr 8 and 0xFF)
        result.write(txId.toInt() and 0xFF)
        result.write(0x01)
        result.write(0x00)
        result.write(0x00)
        result.write(0x01)
        result.write(0x00)
        result.write(0x00)
        result.write(0x00)
        result.write(0x00)
        result.write(0x00)
        result.write(0x00)

        for (part in domain.split(".")) {
            val len = part.length.coerceAtMost(63)
            result.write(len)
            for (i in 0 until len) {
                result.write(part[i].code.coerceAtMost(255))
            }
        }
        result.write(0)
        result.write(0x00)
        result.write(0x01)
        result.write(0x00)
        result.write(0x01)

        return result.toByteArray()
    }








    fun parseDnsResponse(data: ByteArray, txId: Short): Any {
        if (data.size < 12) {
            return "PARSE_ERR"
        }

        val respTxId = ((data[0].toInt() shl 8) or (data[1].toInt() and 0xFF)).toShort()
        if (respTxId != txId) {
            return "PARSE_ERR"
        }

        val flags = ((data[2].toInt() shl 8) or (data[3].toInt() and 0xFF))
        val rcode = flags and 0x0F
        val ancount = ((data[6].toInt() shl 8) or (data[7].toInt() and 0xFF))

        if (rcode == 3) {
            return "NXDOMAIN"
        }
        if (rcode != 0 || ancount == 0) {
            return "PARSE_ERR"
        }

        var offset = 12
        try {
            while (true) {
                if (offset >= data.size) return "PARSE_ERR"
                val length = data[offset].toInt() and 0xFF
                if (length == 0) {
                    offset += 1
                    break
                }
                if (length and 0xC0 == 0xC0) {
                    offset += 2
                    break
                }
                offset += length + 1
            }
            offset += 4
        } catch (e: Exception) {
            return "PARSE_ERR"
        }

        val ips = mutableListOf<String>()
        for (i in 0 until ancount) {
            try {
                if (offset >= data.size) break

                if (data[offset].toInt() and 0xC0 == 0xC0) {
                    offset += 2
                } else {
                    while (offset < data.size && data[offset].toInt() and 0xFF != 0) {
                        offset += (data[offset].toInt() and 0xFF) + 1
                    }
                    if (offset < data.size) offset += 1
                }

                if (offset + 10 > data.size) break

                val rtype = ((data[offset].toInt() shl 8) or (data[offset + 1].toInt() and 0xFF))
                val rdlen =
                    ((data[offset + 8].toInt() shl 8) or (data[offset + 9].toInt() and 0xFF))
                offset += 10

                if (rtype == 1 && rdlen == 4) {
                    val ip =
                        "${data[offset].toInt() and 0xFF}.${data[offset + 1].toInt() and 0xFF}" +
                                ".${data[offset + 2].toInt() and 0xFF}.${data[offset + 3].toInt() and 0xFF}"
                    ips.add(ip)
                }
                offset += rdlen
            } catch (e: Exception) {
                break
            }
        }

        return if (ips.isNotEmpty()) ips else "PARSE_ERR"
    }
}
