package com.lsd.wififrankenstein.ui.wifimap

import kotlin.math.cos
import kotlin.math.sin

class MarkerClusterManager(
    private val maxClusterSize: Int = 1000,
    private val clusterAggressiveness: Float = 1.0f,
    private val preventClusterMerge: Boolean = false,
    private val forcePointSeparation: Boolean = true
) {


    fun createClusters(
        points: List<NetworkPoint>,
        zoomLevel: Double,
        maxPoints: Int = Integer.MAX_VALUE
    ): List<MarkerCluster> {
        if (points.isEmpty()) return emptyList()

        val filteredPoints = if (points.size > maxPoints && maxPoints > 0) {
            points.shuffled().take(maxPoints)
        } else {
            points
        }

        val spreadPoints = spreadOverlappingPoints(filteredPoints)

        var clusterSize = calculateClusterSize(zoomLevel)
        val clusters = mutableListOf<MarkerCluster>()

        val forceSeparate = zoomLevel < 10.0 || preventClusterMerge

        var attempts = 0
        while (attempts < 3) {
            clusters.clear()

            spreadPoints.forEach { point ->
                var addedToExistingCluster = false

                if (!forceSeparate) {

                    for (cluster in clusters) {
                        if (cluster.size >= maxClusterSize) continue

                        val distance = MarkerCluster.calculateDistance(
                            cluster.centerLatitude,
                            cluster.centerLongitude,
                            point.displayLatitude,
                            point.displayLongitude
                        )

                        if (distance <= clusterSize) {
                            cluster.addPoint(point)
                            addedToExistingCluster = true
                            break
                        }
                    }
                }


                if (!addedToExistingCluster) {
                    clusters.add(MarkerCluster().apply { addPoint(point) })
                }
            }

            if (clusters.none { it.size > maxClusterSize }) {
                break
            }

            clusterSize /= 2
            attempts++
        }

        return clusters
    }

    fun spreadOverlappingPoints(points: List<NetworkPoint>): List<NetworkPoint> {
        if (points.isEmpty() || !forcePointSeparation) return points

        val groupedPoints = points.groupBy { "${it.latitude},${it.longitude}" }
        val result = mutableListOf<NetworkPoint>()

        groupedPoints.forEach { (_, pointsAtLocation) ->
            if (pointsAtLocation.size == 1) {

                result.add(pointsAtLocation.first())
            } else {

                val angle = 2 * Math.PI / pointsAtLocation.size
                val offsetRadius = 0.00005

                pointsAtLocation.forEachIndexed { index, point ->
                    val currentAngle = angle * index
                    val offsetLat = offsetRadius * sin(currentAngle)
                    val offsetLon = offsetRadius * cos(currentAngle)


                    val offsetPoint = point.copy(
                        offsetLatitude = offsetLat,
                        offsetLongitude = offsetLon
                    )

                    result.add(offsetPoint)
                }
            }
        }

        return result
    }

    private fun calculateClusterSize(zoomLevel: Double): Double {
        val baseSize = when {
            zoomLevel >= 18 -> 10.0
            zoomLevel >= 17 -> 25.0
            zoomLevel >= 16 -> 100.0
            zoomLevel >= 15 -> 300.0
            zoomLevel >= 14 -> 800.0
            zoomLevel >= 13 -> 1500.0
            zoomLevel >= 12 -> 3000.0
            else -> 5000.0
        }

        val safeAggressiveness = when {
            clusterAggressiveness <= 0.15f -> 0.15f
            clusterAggressiveness > 3.0f -> 3.0f
            else -> clusterAggressiveness
        }

        return baseSize * safeAggressiveness
    }
}