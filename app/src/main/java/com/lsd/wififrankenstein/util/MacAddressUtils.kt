package com.lsd.wififrankenstein.util

import com.lsd.wififrankenstein.util.Log

object MacAddressUtils {
    private const val TAG = "MacAddressUtils"

    fun convertToDecimal(mac: String): Long? {
        return try {
            val cleanMac = mac.replace(Regex("[^a-fA-F0-9]"), "").uppercase()

            when {
                cleanMac.matches(Regex("^[0-9]+$")) -> {
                    val decimal = cleanMac.toLongOrNull()
                    if (decimal != null && decimal <= 0xFFFFFFFFFFFFL) decimal else null
                }
                cleanMac.length == 12 && cleanMac.matches(Regex("^[0-9A-F]+$")) -> {
                    cleanMac.toLongOrNull(16)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting MAC to decimal: $mac", e)
            null
        }
    }

    fun convertToHexString(input: String): String? {
        return try {
            when {
                input.matches(Regex("^[0-9]+$")) -> {
                    val decimal = input.toLongOrNull() ?: return null
                    if (decimal > 0xFFFFFFFFFFFFL) return null
                    String.format("%012X", decimal)
                }
                else -> {
                    val cleanMac = input.replace(Regex("[^a-fA-F0-9]"), "").uppercase()
                    if (cleanMac.length == 12 && cleanMac.matches(Regex("^[0-9A-F]+$"))) {
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

        val cleanInput = input.replace(Regex("[^a-fA-F0-9]"), "").uppercase()
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

        if (input.matches(Regex("^[0-9]+$"))) {
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
        val cleanMac = mac.replace(Regex("[^a-fA-F0-9]"), "")
        return when {
            mac.matches(Regex("^[0-9]+$")) -> {
                try {
                    val decimal = mac.toLong()
                    decimal <= 0xFFFFFFFFFFFFL
                } catch (e: NumberFormatException) {
                    false
                }
            }
            cleanMac.length == 12 -> cleanMac.matches(Regex("^[0-9A-Fa-f]+$"))
            else -> false
        }
    }
}