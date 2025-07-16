package com.lsd.wififrankenstein.ui.wifimap

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs

class MarkerClusterManager(
    private val maxClusterSize: Int = 1000,
    private val clusterAggressiveness: Float = 1.0f,
    private val preventClusterMerge: Boolean = false,
    private val forcePointSeparation: Boolean = true
) {

    fun createClusters(
        points: List<NetworkPoint>,
        zoomLevel: Double
    ): List<MarkerCluster> {
        if (points.isEmpty()) return emptyList()

        val processedPoints = if (forcePointSeparation) {
            spreadOverlappingPointsEfficient(points)
        } else {
            points
        }

        return if (preventClusterMerge) {
            processedPoints.map { point ->
                MarkerCluster().apply { addPoint(point) }
            }
        } else {
            performClustering(processedPoints, zoomLevel)
        }
    }

    private fun performClustering(points: List<NetworkPoint>, zoomLevel: Double): List<MarkerCluster> {
        val clusters = mutableListOf<MarkerCluster>()
        val clusterRadius = calculateClusterRadius(zoomLevel)

        points.forEach { point ->
            var addedToCluster = false

            for (cluster in clusters) {
                if (cluster.size >= maxClusterSize) continue

                val distance = calculateDistance(
                    cluster.centerLatitude,
                    cluster.centerLongitude,
                    point.displayLatitude,
                    point.displayLongitude
                )

                if (distance <= clusterRadius) {
                    cluster.addPoint(point)
                    addedToCluster = true
                    break
                }
            }

            if (!addedToCluster) {
                clusters.add(MarkerCluster().apply { addPoint(point) })
            }
        }

        return clusters
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun calculateClusterRadius(zoomLevel: Double): Double {
        val baseRadius = when {
            zoomLevel >= 18 -> 15.0
            zoomLevel >= 17 -> 30.0
            zoomLevel >= 16 -> 60.0
            zoomLevel >= 15 -> 120.0
            zoomLevel >= 14 -> 250.0
            zoomLevel >= 13 -> 500.0
            zoomLevel >= 12 -> 1000.0
            zoomLevel >= 11 -> 2000.0
            zoomLevel >= 10 -> 4000.0
            zoomLevel >= 9 -> 8000.0
            zoomLevel >= 8 -> 15000.0
            zoomLevel >= 7 -> 30000.0
            zoomLevel >= 6 -> 60000.0
            zoomLevel >= 5 -> 120000.0
            zoomLevel >= 4 -> 250000.0
            zoomLevel >= 3 -> 500000.0
            else -> 1000000.0
        }

        val aggressiveMultiplier = when {
            zoomLevel <= 3 -> 5.0
            zoomLevel <= 5 -> 4.0
            zoomLevel <= 7 -> 3.0
            zoomLevel <= 9 -> 2.5
            zoomLevel <= 11 -> 2.0
            else -> 1.0
        }

        return baseRadius * clusterAggressiveness.coerceIn(0.1f, 10.0f) * aggressiveMultiplier
    }

    fun createAdaptiveClusters(
        points: List<NetworkPoint>,
        zoomLevel: Double,
        mapCenter: Pair<Double, Double>? = null
    ): List<MarkerCluster> {
        if (points.isEmpty()) return emptyList()

        val processedPoints = if (forcePointSeparation) {
            spreadOverlappingPointsEfficient(points)
        } else {
            points
        }

        return if (preventClusterMerge) {
            processedPoints.map { point ->
                MarkerCluster().apply { addPoint(point) }
            }
        } else {
            performAdaptiveClustering(processedPoints, zoomLevel, mapCenter)
        }
    }

    private fun performAdaptiveClustering(
        points: List<NetworkPoint>,
        zoomLevel: Double,
        mapCenter: Pair<Double, Double>?
    ): List<MarkerCluster> {
        val clusters = mutableListOf<MarkerCluster>()
        val baseClusterRadius = calculateClusterRadius(zoomLevel)

        points.forEach { point ->
            var addedToCluster = false
            val distanceFromCenter = mapCenter?.let { center ->
                calculateDistance(center.first, center.second, point.displayLatitude, point.displayLongitude)
            } ?: 0.0

            val distanceMultiplier = when {
                distanceFromCenter > 100000 -> 3.0
                distanceFromCenter > 50000 -> 2.5
                distanceFromCenter > 20000 -> 2.0
                distanceFromCenter > 10000 -> 1.5
                else -> 1.0
            }

            val adaptiveRadius = baseClusterRadius * distanceMultiplier

            for (cluster in clusters) {
                if (cluster.size >= maxClusterSize) continue

                val distance = calculateDistance(
                    cluster.centerLatitude,
                    cluster.centerLongitude,
                    point.displayLatitude,
                    point.displayLongitude
                )

                if (distance <= adaptiveRadius) {
                    cluster.addPoint(point)
                    addedToCluster = true
                    break
                }
            }

            if (!addedToCluster) {
                clusters.add(MarkerCluster().apply { addPoint(point) })
            }
        }

        return clusters
    }

    private fun spreadOverlappingPointsEfficient(points: List<NetworkPoint>): List<NetworkPoint> {
        if (points.isEmpty()) return points

        val positionGroups = mutableMapOf<String, MutableList<NetworkPoint>>()

        points.forEach { point ->
            val key = "${(point.latitude * 100000).toInt()}_${(point.longitude * 100000).toInt()}"
            positionGroups.getOrPut(key) { mutableListOf() }.add(point)
        }

        return positionGroups.flatMap { (_, groupPoints) ->
            when {
                groupPoints.size == 1 -> groupPoints
                groupPoints.size <= 16 -> {
                    val radius = 0.00003
                    val angleStep = 2 * Math.PI / groupPoints.size

                    groupPoints.mapIndexed { index, point ->
                        val angle = angleStep * index
                        point.copy(
                            offsetLatitude = radius * sin(angle),
                            offsetLongitude = radius * cos(angle)
                        )
                    }
                }
                else -> {
                    val gridSize = 4
                    val radius = 0.00002

                    groupPoints.take(16).mapIndexed { index, point ->
                        val row = index / gridSize
                        val col = index % gridSize
                        val offsetLat = (row - gridSize / 2.0) * radius
                        val offsetLon = (col - gridSize / 2.0) * radius

                        point.copy(
                            offsetLatitude = offsetLat,
                            offsetLongitude = offsetLon
                        )
                    }
                }
            }
        }
    }
}