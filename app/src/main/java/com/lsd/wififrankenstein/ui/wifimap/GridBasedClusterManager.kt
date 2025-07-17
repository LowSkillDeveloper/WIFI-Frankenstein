package com.lsd.wififrankenstein.ui.wifimap

import kotlin.math.floor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs

class GridBasedClusterManager(
    private val maxClusterSize: Int = 1000,
    private val clusterAggressiveness: Float = 1.0f,
    private val preventClusterMerge: Boolean = false,
    private val forcePointSeparation: Boolean = true
) {

    private data class GridCell(
        val x: Int,
        val y: Int,
        val points: MutableList<NetworkPoint> = mutableListOf(),
        var centerLatitude: Double = 0.0,
        var centerLongitude: Double = 0.0
    ) {
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
    }

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
            performGridClustering(processedPoints, zoomLevel)
        }
    }

    private fun performGridClustering(
        points: List<NetworkPoint>,
        zoomLevel: Double
    ): List<MarkerCluster> {
        val gridSize = calculateGridSize(zoomLevel)
        val cells = mutableMapOf<Pair<Int, Int>, GridCell>()

        points.forEach { point ->
            val cellX = floor(point.displayLatitude / gridSize).toInt()
            val cellY = floor(point.displayLongitude / gridSize).toInt()
            val key = Pair(cellX, cellY)

            val cell = cells.getOrPut(key) {
                GridCell(cellX, cellY)
            }

            if (cell.points.size < maxClusterSize) {
                cell.addPoint(point)
            }
        }

        return cells.values.map { cell ->
            MarkerCluster(
                centerLatitude = cell.centerLatitude,
                centerLongitude = cell.centerLongitude
            ).apply {
                cell.points.forEach { point ->
                    addPoint(point)
                }
            }
        }
    }

    private fun calculateGridSize(zoomLevel: Double): Double {
        val baseSize = when {
            zoomLevel >= 18 -> 0.00001
            zoomLevel >= 17 -> 0.00002
            zoomLevel >= 16 -> 0.00004
            zoomLevel >= 15 -> 0.00008
            zoomLevel >= 14 -> 0.00016
            zoomLevel >= 13 -> 0.00032
            zoomLevel >= 12 -> 0.00064
            zoomLevel >= 11 -> 0.00512
            zoomLevel >= 10 -> 0.01024
            zoomLevel >= 9 -> 0.02048
            zoomLevel >= 8 -> 0.04096
            zoomLevel >= 7 -> 0.08192
            zoomLevel >= 6 -> 0.16384
            zoomLevel >= 5 -> 0.32768
            zoomLevel >= 4 -> 0.65536
            zoomLevel >= 3 -> 1.31072
            else -> 2.62144
        }

        val aggressivenessFactor = when {
            zoomLevel <= 6 -> clusterAggressiveness * 5.0f
            zoomLevel <= 8 -> clusterAggressiveness * 4.0f
            zoomLevel <= 10 -> clusterAggressiveness * 3.0f
            zoomLevel <= 12 -> clusterAggressiveness * 2.0f
            zoomLevel <= 13 -> clusterAggressiveness * 1.7f
            zoomLevel <= 15 -> clusterAggressiveness * 1.3f
            else -> clusterAggressiveness
        }

        return baseSize * aggressivenessFactor.coerceIn(0.1f, 50.0f)
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
            performAdaptiveGridClustering(processedPoints, zoomLevel, mapCenter)
        }
    }

    private fun performAdaptiveGridClustering(
        points: List<NetworkPoint>,
        zoomLevel: Double,
        mapCenter: Pair<Double, Double>?
    ): List<MarkerCluster> {
        val baseGridSize = calculateGridSize(zoomLevel)
        val cells = mutableMapOf<Pair<Int, Int>, GridCell>()

        points.forEach { point ->
            val distanceFromCenter = mapCenter?.let { center ->
                calculateDistance(
                    center.first, center.second,
                    point.displayLatitude, point.displayLongitude
                )
            } ?: 0.0

            val adaptiveGridSize = when {
                distanceFromCenter > 100000 -> baseGridSize * 3.0
                distanceFromCenter > 50000 -> baseGridSize * 2.5
                distanceFromCenter > 20000 -> baseGridSize * 2.0
                distanceFromCenter > 10000 -> baseGridSize * 1.5
                else -> baseGridSize
            }

            val cellX = floor(point.displayLatitude / adaptiveGridSize).toInt()
            val cellY = floor(point.displayLongitude / adaptiveGridSize).toInt()
            val key = Pair(cellX, cellY)

            val cell = cells.getOrPut(key) {
                GridCell(cellX, cellY)
            }

            if (cell.points.size < maxClusterSize) {
                cell.addPoint(point)
            }
        }

        return cells.values.map { cell ->
            MarkerCluster(
                centerLatitude = cell.centerLatitude,
                centerLongitude = cell.centerLongitude
            ).apply {
                cell.points.forEach { point ->
                    addPoint(point)
                }
            }
        }
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