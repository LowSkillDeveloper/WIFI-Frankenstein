package com.lsd.wififrankenstein.util

import android.annotation.SuppressLint
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

@SuppressLint("DefaultLocale")
fun calculateDistanceString(frequency: Int, level: Int, correctionFactor: Double = 1.0): String {
    val exp = (27.55 - (20 * log10(frequency.toDouble())) + abs(level)) / 20.0
    val distance = 10.0.pow(exp) * correctionFactor
    return String.format("%.2f meters", distance)
}
