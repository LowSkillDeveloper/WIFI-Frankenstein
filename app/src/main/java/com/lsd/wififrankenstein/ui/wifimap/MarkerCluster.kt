package com.lsd.wififrankenstein.ui.wifimap

import kotlin.math.pow
import kotlin.math.sqrt

data class MarkerCluster(
    var centerLatitude: Double = 0.0,
    var centerLongitude: Double = 0.0,
    val points: MutableList<NetworkPoint> = mutableListOf()
) {
    val size: Int get() = points.size

    val databaseCounts: Map<String, Int> get() = points
        .groupBy { it.databaseId }
        .mapValues { it.value.size }

    val isMixedDatabase: Boolean get() = databaseCounts.size > 1

    fun addPoint(point: NetworkPoint) {
        points.add(point)
        recalculateCenter()
    }

    private fun recalculateCenter() {
        if (points.isEmpty()) return

        var sumLat = 0.0
        var sumLon = 0.0
        points.forEach { point ->
            sumLat += point.displayLatitude
            sumLon += point.displayLongitude
        }
        centerLatitude = sumLat / points.size
        centerLongitude = sumLon / points.size
    }

    companion object {
        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadius = 6371000.0

            val lat1Rad = Math.toRadians(lat1)
            val lat2Rad = Math.toRadians(lat2)
            val deltaLat = Math.toRadians(lat2 - lat1)
            val deltaLon = Math.toRadians(lon2 - lon1)

            val a = kotlin.math.sin(deltaLat / 2).pow(2) +
                    kotlin.math.cos(lat1Rad) * kotlin.math.cos(lat2Rad) *
                    kotlin.math.sin(deltaLon / 2).pow(2)

            val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))

            return earthRadius * c
        }
    }
}