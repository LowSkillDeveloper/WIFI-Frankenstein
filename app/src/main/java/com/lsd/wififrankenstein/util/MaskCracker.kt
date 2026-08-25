package com.lsd.wififrankenstein.util

import java.math.BigInteger

object MaskCracker {

    const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val DIGITS = "0123456789"
    const val SPECIALS = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ "
    const val ALL = LOWERCASE + UPPERCASE + DIGITS + SPECIALS

    data class ParsedMask(
        val positions: List<List<Char>>,
        val totalCombinations: Long,
        val exactCombinations: BigInteger,
        val isValid: Boolean,
        val error: String? = null
    )

    fun parse(pattern: String, vararg customCharsets: String): ParsedMask {
        if (pattern.isBlank()) return invalid("mask is empty")

        val customSets = mutableMapOf<Int, List<Char>>()
        customCharsets.forEachIndexed { i, cs ->
            if (cs.isNotEmpty()) customSets[i + 1] = cs.toList()
        }

        val positions = mutableListOf<List<Char>>()
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] == '?' && i + 1 < pattern.length) {
                val next = pattern[i + 1]
                when (next) {
                    'l' -> { positions.add(LOWERCASE.toList()); i += 2; continue }
                    'u' -> { positions.add(UPPERCASE.toList()); i += 2; continue }
                    'd' -> { positions.add(DIGITS.toList()); i += 2; continue }
                    's' -> { positions.add(SPECIALS.toList()); i += 2; continue }
                    'a' -> { positions.add(ALL.toList()); i += 2; continue }
                    in '1'..'4' -> {
                        val cs = customSets[next - '0']
                        if (cs.isNullOrEmpty()) {
                            return invalid("custom charset ?$next is not defined")
                        }
                        positions.add(cs); i += 2; continue
                    }
                    '?' -> { positions.add(listOf('?')); i += 2; continue }
                }
            }
            positions.add(listOf(pattern[i]))
            i++
        }

        if (positions.isEmpty()) return invalid("mask has no character positions")

        var exact = BigInteger.ONE
        for (pos in positions) {
            exact = exact.multiply(BigInteger.valueOf(pos.size.toLong()))
        }
        val saturated = if (exact > BigInteger.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE else exact.toLong()

        return ParsedMask(positions, saturated, exact, true, null)
    }

    fun generateAt(parsed: ParsedMask, index: Long): String {
        require(index >= 0) { "negative index" }
        require(index < parsed.totalCombinations || parsed.totalCombinations == Long.MAX_VALUE) {
            "index $index out of range ${parsed.totalCombinations}"
        }

        val sb = StringBuilder(parsed.positions.size)
        var remaining = index
        for (pos in parsed.positions.asReversed()) {
            val size = pos.size
            if (size <= 1) { sb.append(pos.firstOrNull() ?: '?'); continue }
            val digit = (remaining % size).toInt()
            remaining /= size
            sb.insert(0, pos[digit])
        }
        return sb.toString()
    }

    fun estimateTimeMs(combinations: Long, pwPerSecond: Double): Long {
        if (pwPerSecond <= 0 || combinations <= 0) return 0
        if (combinations > Long.MAX_VALUE / 1000) return Long.MAX_VALUE
        return (combinations / pwPerSecond * 1000).toLong().coerceAtLeast(0)
    }

    private fun invalid(msg: String) = ParsedMask(emptyList(), 0, BigInteger.ZERO, false, msg)
}
