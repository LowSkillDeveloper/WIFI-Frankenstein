package com.lsd.wififrankenstein.ui.dbsetup

import com.lsd.wififrankenstein.util.MacAddressUtils

object ColumnAutoMapper {

    enum class AdminMode { COMBINED, SPLIT, NONE }

    data class AutoMapResult(
        val map: Map<String, String>,
        val adminMode: AdminMode
    )

    private val DATE_RE = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
    private val PIN_RE = Regex("^\\d{7,8}$")
    private val EPOCH_RE = Regex("^\\d{10,13}$")
    private val COMBINED_RE = Regex("^[^:]+:[^:]+$")
    private val DIGITS_ONLY_RE = Regex("^\\d+$")
    private val NORMALIZE_RE = Regex("[_\\-. ]")

    private val ADMIN_COMBINED_STRONG = listOf(
        "authorization", "cred", "credential", "adminpanel", "logininfo"
    )
    private val MAC_STRONG = listOf(
        "bssid", "macaddr", "macaddress", "macadress", "macaddres", "hwaddr", "hwaddress",
        "physaddr", "ethernet", "apmac", "stationmac", "mac"
    )
    private val ESSID_STRONG = listOf(
        "essid", "ssid", "essname", "wifiname", "wifissid", "networkname", "netname", "apname"
    )
    private val ESSID_WEAK = listOf("name")
    private val WIFI_PASS_STRONG = listOf(
        "wifipass",
        "wifipassword",
        "wifikey",
        "wifipwd",
        "wpa",
        "passphrase",
        "passkey",
        "psk",
        "networkkey"
    )
    private val WIFI_PASS_WEAK = listOf("password", "pass", "pwd", "passwd", "key", "secret")
    private val WPS_PIN_STRONG = listOf(
        "wpspin", "wpspincode", "wpscode", "wpskey", "pinwps", "wpspass", "wpspincode"
    )
    private val WPS_PIN_WEAK = listOf("wps")
    private val LAT_STRONG = listOf("lat")
    private val LON_STRONG = listOf("lon", "lng", "longitude", "long")
    private val SECURITY_STRONG = listOf(
        "security",
        "secure",
        "encryption",
        "encrypt",
        "cipher",
        "authmode",
        "capability",
        "capabilities",
        "capab"
    )
    private val SECURITY_WEAK = listOf("sec")
    private val TIMESTAMP_STRONG =
        listOf("timestamp", "date", "created", "updated", "lastseen", "time")
    private val TIMESTAMP_WEAK = listOf("scan", "added", "when")
    private val ADMIN_LOGIN_STRONG =
        listOf("adminlogin", "adminuser", "username", "userid", "account", "login")
    private val ADMIN_LOGIN_WEAK = listOf("user", "uname")
    private val ADMIN_PASS_STRONG = listOf(
        "adminpass", "adminpassword", "adminpwd", "userpass", "loginpass", "adminsecret"
    )
    private val ADMIN_PASS_WEAK = listOf("password", "pass", "pwd", "passwd", "secret")

    data class ColumnStats(
        val values: List<String>,
        val fillRatio: Double
    )

    private data class ColumnData(
        val name: String,
        val n: String,
        val samples: List<String>,
        val fill: Double
    )

    fun autoMap(
        columnNames: List<String>,
        stats: (String) -> ColumnStats
    ): AutoMapResult {
        val data = columnNames.associate { col ->
            val s = stats(col)
            col to ColumnData(col, norm(col), s.values, s.fillRatio)
        }

        val used = mutableSetOf<String>()
        val result = mutableMapOf<String, String>()
        var adminMode = AdminMode.NONE

        val combined = bestCombined(data, used)
        if (combined != null) {
            result["admin_panel"] = combined
            used.add(combined)
            adminMode = AdminMode.COMBINED
        } else {
            val split = bestSplit(data, used)
            if (split != null) {
                result["admin_login"] = split.first
                result["admin_pass"] = split.second
                used.add(split.first)
                used.add(split.second)
                adminMode = AdminMode.SPLIT
            }
        }

        val order: List<Pair<String, Pair<(ColumnData) -> Int, (ColumnData) -> Int>>> = listOf(
            "mac" to ({ c: ColumnData ->
                nameScore(
                    c.n,
                    MAC_STRONG,
                    emptyList()
                )
            } to { c: ColumnData -> macContent(c.samples) }),
            "latitude" to ({ c: ColumnData ->
                nameScore(
                    c.n,
                    LAT_STRONG,
                    emptyList()
                )
            } to { c: ColumnData -> coordContent(c.samples, isLat = true) }),
            "longitude" to ({ c: ColumnData ->
                nameScore(
                    c.n,
                    LON_STRONG,
                    emptyList()
                )
            } to { c: ColumnData -> coordContent(c.samples, isLat = false) }),
            "wps_pin" to ({ c: ColumnData ->
                nameScore(
                    c.n,
                    WPS_PIN_STRONG,
                    WPS_PIN_WEAK
                )
            } to { c: ColumnData -> wpsPinContent(c.samples) }),
            "essid" to ({ c: ColumnData ->
                if ("bssid" in c.n) 0 else nameScore(
                    c.n,
                    ESSID_STRONG,
                    ESSID_WEAK
                )
            } to { c: ColumnData -> essidContent(c.samples) }),
            "wifi_pass" to ({ c: ColumnData ->
                if ("admin" in c.n) 0 else nameScore(
                    c.n,
                    WIFI_PASS_STRONG,
                    WIFI_PASS_WEAK
                )
            } to { c: ColumnData -> wifiPassContent(c.samples) }),
            "security_type" to ({ c: ColumnData ->
                nameScore(
                    c.n,
                    SECURITY_STRONG,
                    SECURITY_WEAK
                )
            } to { c: ColumnData -> securityContent(c.samples) }),
            "timestamp" to ({ c: ColumnData ->
                nameScore(
                    c.n,
                    TIMESTAMP_STRONG,
                    TIMESTAMP_WEAK
                )
            } to { c: ColumnData -> timestampContent(c.samples) })
        )

        order.forEach { (key, pair) ->
            val (nameFn, contentFn) = pair
            val best = data.values
                .filter { it.name !in used }
                .mapNotNull { c ->
                    val ns = nameFn(c)
                    val cs = contentFn(c)
                    val total = ns + cs + fillBonus(c.fill)
                    if ((ns >= 55 || cs >= 25) && total >= 60) Triple(c.name, total, ns) else null
                }
                .maxWithOrNull(
                    compareBy<Triple<String, Int, Int>>({ it.second }).thenByDescending { it.third }
                )
            if (best != null) {
                result[key] = best.first
                used.add(best.first)
            }
        }

        return AutoMapResult(result, adminMode)
    }

    private fun bestCombined(data: Map<String, ColumnData>, used: Set<String>): String? {
        return data.values
            .filter { it.name !in used }
            .mapNotNull { c ->
                val ns = nameScore(c.n, ADMIN_COMBINED_STRONG, emptyList())
                val cs = combinedContent(c.samples)
                val total = ns + cs + fillBonus(c.fill)
                if ((ns >= 55 || cs >= 25) && total >= 60) Triple(c.name, total, ns) else null
            }
            .maxWithOrNull(
                compareBy<Triple<String, Int, Int>>({ it.second }).thenByDescending { it.third }
            )
            ?.first
    }

    private fun bestSplit(data: Map<String, ColumnData>, used: Set<String>): Pair<String, String>? {
        val login = data.values
            .filter { it.name !in used && it.fill >= 0.3 }
            .mapNotNull { c ->
                val ns = nameScore(c.n, ADMIN_LOGIN_STRONG, ADMIN_LOGIN_WEAK)
                val total = ns + fillBonus(c.fill)
                if (ns >= 35 && total >= 60) Triple(c.name, total, ns) else null
            }
            .maxWithOrNull(
                compareBy<Triple<String, Int, Int>>({ it.second }).thenByDescending { it.third }
            )
            ?.first ?: return null

        val pass = data.values
            .filter {
                it.name !in used && it.name != login && it.fill >= 0.3 &&
                        "wifi" !in it.n && "wlan" !in it.n && "psk" !in it.n
            }
            .mapNotNull { c ->
                val ns = nameScore(c.n, ADMIN_PASS_STRONG, ADMIN_PASS_WEAK)
                val total = ns + fillBonus(c.fill)
                if (ns >= 35 && total >= 60) Triple(c.name, total, ns) else null
            }
            .maxWithOrNull(
                compareBy<Triple<String, Int, Int>>({ it.second }).thenByDescending { it.third }
            )
            ?.first ?: return null

        return login to pass
    }

    private fun norm(name: String): String = name.lowercase().replace(NORMALIZE_RE, "")

    private fun nameScore(n: String, strong: List<String>, weak: List<String>): Int {
        return when {
            strong.any { n == it } -> 70
            strong.any { it in n } -> 55
            weak.any { it in n } -> 35
            else -> 0
        }
    }

    private fun fillBonus(fill: Double): Int = when {
        fill >= 0.6 -> 25
        fill >= 0.3 -> 10
        else -> -20
    }

    private fun nonBlank(samples: List<String>): List<String> = samples.filter { it.isNotBlank() }

    private fun isPlausibleMacValue(v: String): Boolean {
        val t = v.trim()
        if (t in setOf("0", "1", "2", "3")) return false
        if (t.matches(DIGITS_ONLY_RE)) {
            val d = t.toLongOrNull() ?: return false

            return d >= 0x100000000L && d <= 0xFFFFFFFFFFFFL
        }
        return MacAddressUtils.isValidMacAddress(t)
    }

    private fun macContent(samples: List<String>): Int {
        val vals = nonBlank(samples)
        if (vals.isEmpty()) return 0
        val valid = vals.count { isPlausibleMacValue(it) }
        return if (valid.toDouble() / vals.size >= 0.6) 50 else 0
    }

    private fun coordContent(samples: List<String>, isLat: Boolean): Int {
        val vals = nonBlank(samples)
        if (vals.isEmpty()) return 0
        val valid = vals.count { v ->
            val d = v.trim().toDoubleOrNull() ?: return@count false
            if (kotlin.math.abs(d) < 1e-9) return@count false
            if (isLat) d in -90.0..90.0 else d in -180.0..180.0
        }
        return if (valid.toDouble() / vals.size >= 0.6) 50 else 0
    }

    private fun wpsPinContent(samples: List<String>): Int {
        val vals = nonBlank(samples)
        if (vals.isEmpty()) return 0
        val valid = vals.count { v ->
            val t = v.trim()
            t !in setOf("0", "1") && t.matches(PIN_RE)
        }
        return if (valid.toDouble() / vals.size >= 0.6) 50 else 0
    }

    private fun timestampContent(samples: List<String>): Int {
        val vals = nonBlank(samples)
        if (vals.isEmpty()) return 0
        val valid = vals.count { v ->
            val t = v.trim()
            t.matches(DATE_RE) || t.matches(EPOCH_RE)
        }
        return if (valid.toDouble() / vals.size >= 0.5) 45 else 0
    }

    private fun securityContent(samples: List<String>): Int {
        val vals = nonBlank(samples)
        if (vals.isEmpty()) return 0
        val valid = vals.count { v ->
            v.contains("WPA") || v.contains("WEP") || v.contains("PSK") || v.contains("CCMP") ||
                    v.contains("OPEN") || v.contains("TKIP") || v.contains("[")
        }
        return if (valid.toDouble() / vals.size >= 0.3) 35 else 0
    }

    private fun combinedContent(samples: List<String>): Int {
        val vals = nonBlank(samples)
        if (vals.isEmpty()) return 0
        val valid = vals.count { v ->
            val t = v.trim()
            if (t.contains("0.0.0.0")) return@count false
            t.matches(COMBINED_RE)
        }
        return if (valid.toDouble() / vals.size >= 0.6) 40 else 0
    }

    private fun essidContent(samples: List<String>): Int {
        val vals = nonBlank(samples)
        if (vals.isEmpty()) return 0
        val unique = vals.distinct().size
        if (unique.toDouble() / vals.size < 0.5) return 0
        val textValid = vals.count { v ->
            val t = v.trim()
            t.length in 1..64 &&
                    !t.matches(DIGITS_ONLY_RE) &&
                    !MacAddressUtils.isValidMacAddress(t) &&
                    !t.matches(PIN_RE) &&
                    !t.matches(DATE_RE)
        }
        return if (textValid.toDouble() / vals.size >= 0.7) 25 else 0
    }

    private fun wifiPassContent(samples: List<String>): Int {
        val vals = nonBlank(samples)
        if (vals.isEmpty()) return 0
        val valid = vals.count { v ->
            val t = v.trim()
            t.length >= 8 &&
                    !t.matches(DIGITS_ONLY_RE) &&
                    !MacAddressUtils.isValidMacAddress(t) &&
                    !t.matches(PIN_RE) &&
                    !t.matches(DATE_RE)
        }
        return if (valid.toDouble() / vals.size >= 0.5) 30 else 0
    }
}
