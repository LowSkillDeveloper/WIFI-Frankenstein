package com.lsd.wififrankenstein.ui.ipranges

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IpRangeManager(context: Context) : SQLiteOpenHelper(context, "ip_ranges.db", null, 1) {

    private val rirClient = RirClient()
    private val whoisParser = WhoisParser()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ranges (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                startIP INTEGER NOT NULL,
                endIP INTEGER NOT NULL,
                netname TEXT NOT NULL,
                descr TEXT NOT NULL,
                country TEXT NOT NULL
            )
        """)

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ranges_ip ON ranges(startIP, endIP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS ranges")
        onCreate(db)
    }

    suspend fun getIpRangeInfo(ip: Long): IpRange? = withContext(Dispatchers.IO) {
        val cached = getCachedRange(ip)
        if (cached != null) {
            Log.d("IpRangeManager", "Found cached range for IP: ${longToIp(ip)}")
            return@withContext cached
        }

        val ipString = longToIp(ip)
        Log.d("IpRangeManager", "Querying RIR for IP: $ipString")

        if (isPrivateIP(ip)) {
            return@withContext createPrivateRange(ip)
        }

        val range = queryRangeFromRir(ipString)
        if (range != null) {
            cacheRange(range)
            Log.d("IpRangeManager", "Cached new range: ${range.netname}")
        }

        range
    }

    private fun getCachedRange(ip: Long): IpRange? {
        return readableDatabase.rawQuery(
            "SELECT startIP, endIP, netname, descr, country FROM ranges WHERE startIP <= ? AND endIP >= ? LIMIT 1",
            arrayOf(ip.toString(), ip.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                IpRange(
                    startIP = cursor.getLong(0),
                    endIP = cursor.getLong(1),
                    netname = cursor.getString(2),
                    description = cursor.getString(3),
                    country = cursor.getString(4)
                )
            } else {
                null
            }
        }
    }

    private suspend fun queryRangeFromRir(ip: String): IpRange? {
        val rir = rirClient.getRirForIp(ip) ?: return null
        Log.d("IpRangeManager", "Determined RIR: $rir for IP: $ip")

        val rdapResponse = rirClient.fetchRdap(rir.rdapUrl, ip)
        if (rdapResponse?.port43 != null) {
            Log.d("IpRangeManager", "Got RDAP response, querying WHOIS: ${rdapResponse.port43}")

            val whoisRequest = if (rir == RIR.ARIN) "n + $ip" else ip
            val whoisResponse = rirClient.fetchWhois(rir.whoisServer, whoisRequest)

            if (whoisResponse != null) {
                val parsed = whoisParser.parseWhoisResponse(whoisResponse, rir)
                if (parsed != null) {
                    Log.d("IpRangeManager", "Successfully parsed WHOIS response")
                    return parsed
                }
            }
        }

        Log.w("IpRangeManager", "Failed to get range info for IP: $ip")
        return null
    }

    private fun cacheRange(range: IpRange) {
        if (range.endIP - range.startIP >= 0x00FFFFFF) {
            Log.d("IpRangeManager", "Range too large, not caching: ${range.netname}")
            return
        }

        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO ranges (startIP, endIP, netname, descr, country) VALUES (?, ?, ?, ?, ?)",
            arrayOf(range.startIP, range.endIP, range.netname, range.description, range.country)
        )
    }

    private fun createPrivateRange(ip: Long): IpRange {
        val networkAddress = ip and 0xFFFF0000L
        return IpRange(
            startIP = networkAddress,
            endIP = networkAddress or 0xFFFFL,
            netname = "Private Network",
            description = "Local IP range - Private network address space",
            country = ""
        )
    }

    private fun isPrivateIP(ip: Long): Boolean {
        return (ip >= 0x0A000000L && ip < 0x0B000000L) ||      // 10.0.0.0/8
                (ip >= 0xAC100000L && ip < 0xAC200000L) ||      // 172.16.0.0/12
                (ip >= 0xC0A80000L && ip < 0xC0A90000L) ||      // 192.168.0.0/16
                (ip >= 0x7F000000L && ip < 0x80000000L) ||      // 127.0.0.0/8
                (ip >= 0xA9FE0000L && ip < 0xA9FF0000L)         // 169.254.0.0/16
    }

    private fun longToIp(ip: Long): String {
        val positiveIp = if (ip < 0) ip + 4294967296L else ip
        return "${(positiveIp shr 24) and 0xFF}.${(positiveIp shr 16) and 0xFF}.${(positiveIp shr 8) and 0xFF}.${positiveIp and 0xFF}"
    }

    fun prettyRange(startIP: Long, endIP: Long): String {
        val diff = startIP xor endIP
        val diffBinary = diff.toString(2)

        if (!diffBinary.contains('0') && (startIP and endIP) == startIP) {
            val maskBits = 32 - diffBinary.length
            return "${longToIp(startIP)}/$maskBits"
        }

        return "${longToIp(startIP)}-${longToIp(endIP)}"
    }
}