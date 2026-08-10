package com.lsd.wififrankenstein.util

import java.util.Collections

object MacAddressUtils {
    private const val TAG = "MacAddressUtils"
    private val MAC_CLEAN_REGEX = Regex("[^a-fA-F0-9]")
    private val DECIMAL_ONLY_REGEX = Regex("^[0-9]+$")
    private val HEX_12_REGEX = Regex("^[0-9A-F]+$")
    private val HEX_12_MIXED_REGEX = Regex("^[0-9A-Fa-f]+$")

    private val macDecimalCache = Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
                return size > 256
            }
        }
    )

    fun convertToDecimal(mac: String): Long? {
        val cleanInput = mac.replace(MAC_CLEAN_REGEX, "").uppercase()
        if (cleanInput.isEmpty()) return null

        synchronized(macDecimalCache) {
            macDecimalCache[cleanInput]?.let { return it }
        }

        val result = try {
            when {
                cleanInput.matches(DECIMAL_ONLY_REGEX) -> {
                    val decimal = cleanInput.toLongOrNull()
                    if (decimal != null && decimal <= 0xFFFFFFFFFFFFL) decimal else null
                }

                cleanInput.length == 12 && cleanInput.matches(HEX_12_REGEX) -> {
                    cleanInput.toLongOrNull(16)
                }

                else -> null
            }
        } catch (e: Exception) {
            null
        }

        if (result != null) {
            synchronized(macDecimalCache) {
                if (macDecimalCache.size < 256) {
                    macDecimalCache[cleanInput] = result
                }
            }
        }
        return result
    }

    fun convertToHexString(input: String): String? {
        return try {
            when {
                input.matches(DECIMAL_ONLY_REGEX) -> {
                    val decimal = input.toLongOrNull() ?: return null
                    if (decimal > 0xFFFFFFFFFFFFL) return null
                    String.format("%012X", decimal)
                }

                else -> {
                    val cleanMac = input.replace(MAC_CLEAN_REGEX, "").uppercase()
                    if (cleanMac.length == 12 && cleanMac.matches(HEX_12_REGEX)) {
                        cleanMac
                    } else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting to hex string: $input", e)
            null
        }
    }

    fun formatToColonSeparated(input: String): String? {
        val hexString = convertToHexString(input) ?: return null
        return hexString.chunked(2).joinToString(":")
    }

    fun generateAllFormats(input: String): List<String> {
        val formats = mutableSetOf<String>()

        formats.add(input.trim())

        val cleanInput = input.replace(MAC_CLEAN_REGEX, "").uppercase()
        if (cleanInput.isNotEmpty()) {
            formats.add(cleanInput)
            formats.add(cleanInput.lowercase())

            if (cleanInput.length == 12) {
                val colonFormat = cleanInput.chunked(2).joinToString(":")
                val dashFormat = cleanInput.chunked(2).joinToString("-")
                formats.add(colonFormat)
                formats.add(dashFormat)
                formats.add(colonFormat.lowercase())
                formats.add(dashFormat.lowercase())

                try {
                    val decimal = cleanInput.toLong(16)
                    formats.add(decimal.toString())
                } catch (e: NumberFormatException) {
                    Log.d(TAG, "Could not convert $cleanInput to decimal")
                }
            }
        }

        if (input.matches(DECIMAL_ONLY_REGEX)) {
            try {
                val decimal = input.toLong()
                if (decimal <= 0xFFFFFFFFFFFFL) {
                    val hex = String.format("%012X", decimal)
                    formats.add(hex)
                    formats.add(hex.lowercase())
                    formats.add(hex.chunked(2).joinToString(":"))
                    formats.add(hex.chunked(2).joinToString("-"))
                    formats.add(hex.lowercase().chunked(2).joinToString(":"))
                    formats.add(hex.lowercase().chunked(2).joinToString("-"))
                }
            } catch (e: NumberFormatException) {
                Log.d(TAG, "Could not convert decimal $input to hex")
            }
        }

        return formats.filter { it.isNotEmpty() }.distinct()
    }

    fun isValidMacAddress(mac: String): Boolean {
        val cleanMac = mac.replace(MAC_CLEAN_REGEX, "")
        return when {
            mac.matches(DECIMAL_ONLY_REGEX) -> {
                try {
                    val decimal = mac.toLong()
                    decimal <= 0xFFFFFFFFFFFFL
                } catch (e: NumberFormatException) {
                    false
                }
            }

            cleanMac.length == 12 -> cleanMac.matches(HEX_12_MIXED_REGEX)
            else -> false
        }
    }
}