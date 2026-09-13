package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import java.util.Locale

/**
 * Pure helpers behind the personal-map prefix search.
 *
 * Deliberately free of Android dependencies so the search-key logic can be
 * unit-tested on the JVM. See [PersonalMapDbHelper.searchPersonalRecords].
 */
object PersonalSearchKeys {

    private val NON_HEX_REGEX = Regex("[^0-9a-fA-F]")

    /** SSID search key: Unicode-aware lowercase + trim. */
    fun normalizeSsid(ssid: String): String =
        ssid.trim().lowercase(Locale.ROOT)

    /** BSSID search key: digits/hex only, lowercase (partial input allowed). */
    fun normalizeMac(mac: String): String =
        mac.replace(NON_HEX_REGEX, "").lowercase(Locale.ROOT)

    /**
     * True when the query should also be matched against BSSID: a bare hex
     * prefix (>= 2 chars) or anything containing a MAC separator.
     */
    fun looksLikeMacQuery(raw: String, macNorm: String): Boolean {
        if (macNorm.length < 2) return false
        if (raw.any { it == ':' || it == '-' || it == '.' }) return true
        val compact = raw.filterNot { it.isWhitespace() }
        return compact.isNotEmpty() && compact.length == macNorm.length
    }

    /**
     * Bounds for a BINARY-collation prefix range: every string that starts with
     * [prefix] is >= lo and < hi. Returns null for an empty prefix, and a null
     * upper bound when the prefix cannot be incremented (all U+FFFF).
     */
    fun prefixBounds(prefix: String): Pair<String, String?>? {
        if (prefix.isEmpty()) return null
        val chars = prefix.toCharArray()
        var i = chars.size - 1
        while (i >= 0) {
            val code = chars[i].code
            if (code < 0xFFFF) {
                chars[i] = (code + 1).toChar()
                return prefix to String(chars, 0, i + 1)
            }
            i--
        }
        return prefix to null
    }
}
