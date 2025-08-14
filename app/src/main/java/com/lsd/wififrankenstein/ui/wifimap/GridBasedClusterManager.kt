package com.lsd.wififrankenstein.ui.wifimap

import org.osmdroid.util.BoundingBox
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class GridBasedClusterManager(
    private val maxClusterSize: Int = 1000,
    private val clusterAggressiveness: Float = 1.0f,
    private val preventClusterMerge: Boolean = false,
    private val forcePointSeparation: Boolean = true
) {
    private var lastZoomLevel = -1.0
    private var cachedClusters: List<MarkerCluster> = emptyList()
    private var cachedPoints: List<NetworkPoint> = emptyList()
    private var cachedBounds: BoundingBox? = null

    private val spatialIndex = mutableMapOf<String, MutableList<NetworkPoint>>()
    private val gridCellSize = 0.01

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

        if (canUseCachedClusters(zoomLevel, points)) {
            return cachedClusters
        }

        val processedPoints = if (forcePointSeparation) {
            spreadOverlappingPointsEfficient(points)
        } else {
            points
        }

        val clusters = if (preventClusterMerge) {
            processedPoints.map { point ->
                MarkerCluster().apply { addPoint(point) }
            }
        } else {
            if (clusterAggressiveness >= 3.5f) {
                performSuperAggressiveClustering(processedPoints, zoomLevel)
            } else {
                performGridClustering(processedPoints, zoomLevel)
            }
        }

        updateCache(zoomLevel, points, clusters)
        return clusters
    }

    private fun canUseCachedClusters(zoomLevel: Double, points: List<NetworkPoint>): Boolean {
        return false
    }

    private fun updateCache(zoomLevel: Double, points: List<NetworkPoint>, clusters: List<MarkerCluster>) {
        lastZoomLevel = zoomLevel
        cachedClusters = clusters
        cachedPoints = points

        if (points.isNotEmpty()) {
            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLon = points.minOf { it.longitude }
            val maxLon = points.maxOf { it.longitude }

            val baseBounds = BoundingBox(maxLat, maxLon, minLat, minLon)
            cachedBounds = baseBounds.increaseByScale(1.5f)
        }
    }

    fun canUsePointsCache(requestedBounds: BoundingBox): Boolean {
        return cachedBounds != null && cachedPoints.isNotEmpty() &&
                isAreaContainedIn(requestedBounds, cachedBounds!!)
    }

    fun getCachedPointsInBounds(requestedBounds: BoundingBox): List<NetworkPoint> {
        return cachedPoints.filter { point ->
            requestedBounds.contains(point.latitude, point.longitude)
        }
    }

    private fun isAreaContainedIn(innerBox: BoundingBox, outerBox: BoundingBox): Boolean {
        return outerBox.contains(innerBox.latNorth, innerBox.lonEast) &&
                outerBox.contains(innerBox.latSouth, innerBox.lonWest)
    }

    fun clearCacheOnHighZoom(zoomLevel: Double) {
        if (zoomLevel >= 14.0 && lastZoomLevel < 14.0) {
            cachedClusters = emptyList()
            lastZoomLevel = -1.0
        }
    }

    private fun buildSpatialIndex(points: List<NetworkPoint>) {
        spatialIndex.clear()
        points.forEach { point ->
            val cellKey = getSpatialCellKey(point.latitude, point.longitude)
            spatialIndex.getOrPut(cellKey) { mutableListOf() }.add(point)
        }
    }

    private fun getSpatialCellKey(lat: Double, lon: Double): String {
        val cellLat = (lat / gridCellSize).toInt()
        val cellLon = (lon / gridCellSize).toInt()
        return "$cellLat,$cellLon"
    }

    private fun getPointsInArea(bounds: BoundingBox): List<NetworkPoint> {
        val result = mutableListOf<NetworkPoint>()

        val minCellLat = (bounds.latSouth / gridCellSize).toInt()
        val maxCellLat = (bounds.latNorth / gridCellSize).toInt()
        val minCellLon = (bounds.lonWest / gridCellSize).toInt()
        val maxCellLon = (bounds.lonEast / gridCellSize).toInt()

        for (cellLat in minCellLat..maxCellLat) {
            for (cellLon in minCellLon..maxCellLon) {
                val cellKey = "$cellLat,$cellLon"
                spatialIndex[cellKey]?.let { cellPoints ->
                    result.addAll(cellPoints.filter { point ->
                        bounds.contains(point.latitude, point.longitude)
                    })
                }
            }
        }

        return result
    }

    fun hasSignificantAreaChange(newBounds: BoundingBox): Boolean {
        val currentBounds = cachedBounds ?: return true

        if (!boundsIntersect(currentBounds, newBounds)) {
            return true
        }

        val currentArea = (currentBounds.latNorth - currentBounds.latSouth) *
                (currentBounds.lonEast - currentBounds.lonWest)
        val newArea = (newBounds.latNorth - newBounds.latSouth) *
                (newBounds.lonEast - newBounds.lonWest)

        return kotlin.math.abs(newArea - currentArea) / currentArea > 0.2
    }

    private fun boundsIntersect(bounds1: BoundingBox, bounds2: BoundingBox): Boolean {
        return !(bounds1.latNorth < bounds2.latSouth ||
                bounds1.latSouth > bounds2.latNorth ||
                bounds1.lonEast < bounds2.lonWest ||
                bounds1.lonWest > bounds2.lonEast)
    }

    private fun calculatePointsCenter(points: List<NetworkPoint>): Pair<Double, Double> {
        if (points.isEmpty()) return Pair(0.0, 0.0)

        val avgLat = points.sumOf { it.displayLatitude } / points.size
        val avgLon = points.sumOf { it.displayLongitude } / points.size
        return Pair(avgLat, avgLon)
    }

    private fun performGridClustering(
        points: List<NetworkPoint>,
        zoomLevel: Double
    ): List<MarkerCluster> {
        val gridSize = calculateGridSize(zoomLevel)

        val databaseGroups = points.groupBy { it.databaseId }
        val allClusters = mutableListOf<MarkerCluster>()

        databaseGroups.forEach { (databaseId, dbPoints) ->
            val cells = mutableMapOf<Pair<Int, Int>, GridCell>()

            dbPoints.forEach { point ->
                val cellX = floor(point.displayLatitude / gridSize).toInt()
                val cellY = floor(point.displayLongitude / gridSize).toInt()
                val key = Pair(cellX, cellY)

                val cell = cells.getOrPut(key) {
                    GridCell(cellX, cellY)
                }
                cell.addPoint(point)
            }

            val databaseClusters = cells.values.map { cell ->
                MarkerCluster(
                    centerLatitude = cell.centerLatitude,
                    centerLongitude = cell.centerLongitude
                ).apply {
                    cell.points.forEach { point ->
                        addPoint(point)
                    }
                }
            }

            allClusters.addAll(databaseClusters)
        }

        val separatedClusters = separateOverlappingClusters(allClusters, databaseGroups)

        return if (clusterAggressiveness >= 1.5f) {
            mergeClustersToTargetSizePerDatabase(separatedClusters, zoomLevel, databaseGroups)
        } else {
            separatedClusters.filter { it.size <= maxClusterSize }
        }
    }

    private fun separateOverlappingClusters(
        clusters: List<MarkerCluster>,
        databaseGroups: Map<String, List<NetworkPoint>>
    ): List<MarkerCluster> {
        if (clusters.isEmpty()) return clusters

        val prioritizedDatabases = databaseGroups.entries
            .sortedBy { it.value.size }
            .map { it.key }

        val positionGroups = mutableMapOf<String, MutableList<MarkerCluster>>()

        clusters.forEach { cluster ->
            val key = "${(cluster.centerLatitude * 10000).toInt()}_${(cluster.centerLongitude * 10000).toInt()}"
            positionGroups.getOrPut(key) { mutableListOf() }.add(cluster)
        }

        return positionGroups.flatMap { (_, groupClusters) ->
            when {
                groupClusters.size == 1 -> groupClusters
                else -> {
                    val sortedClusters = groupClusters.sortedBy { cluster ->
                        val databaseId = cluster.points.firstOrNull()?.databaseId ?: ""
                        prioritizedDatabases.indexOf(databaseId).takeIf { it >= 0 } ?: Int.MAX_VALUE
                    }

                    sortedClusters.mapIndexed { index, cluster ->
                        if (index == 0) {
                            cluster
                        } else {
                            val radius = 0.0002 * (index + 1)
                            val angle = (2 * Math.PI * index) / groupClusters.size
                            val offsetLat = radius * sin(angle)
                            val offsetLon = radius * cos(angle)

                            MarkerCluster(
                                centerLatitude = cluster.centerLatitude + offsetLat,
                                centerLongitude = cluster.centerLongitude + offsetLon
                            ).apply {
                                cluster.points.forEach { point ->
                                    addPoint(point.copy(
                                        offsetLatitude = point.offsetLatitude + offsetLat,
                                        offsetLongitude = point.offsetLongitude + offsetLon
                                    ))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mergeClustersToTargetSizePerDatabase(
        clusters: List<MarkerCluster>,
        zoomLevel: Double,
        databaseGroups: Map<String, List<NetworkPoint>>
    ): List<MarkerCluster> {
        val clustersByDatabase = clusters.groupBy { cluster ->
            cluster.points.firstOrNull()?.databaseId ?: ""
        }

        val mergedClusters = mutableListOf<MarkerCluster>()

        clustersByDatabase.forEach { (databaseId, databaseClusters) ->
            val targetClusterCount = when {
                clusterAggressiveness >= 4.0f -> (databaseClusters.size * 0.1).toInt().coerceAtLeast(1)
                clusterAggressiveness >= 3.0f -> (databaseClusters.size * 0.2).toInt().coerceAtLeast(1)
                clusterAggressiveness >= 2.5f -> (databaseClusters.size * 0.3).toInt().coerceAtLeast(1)
                clusterAggressiveness >= 2.0f -> (databaseClusters.size * 0.5).toInt().coerceAtLeast(1)
                else -> (databaseClusters.size * 0.7).toInt().coerceAtLeast(1)
            }

            val mutableDatabaseClusters = databaseClusters.toMutableList()

            while (mutableDatabaseClusters.size > targetClusterCount && mutableDatabaseClusters.size > 1) {
                var minDistance = Double.MAX_VALUE
                var mergeIndex1 = -1
                var mergeIndex2 = -1

                for (i in mutableDatabaseClusters.indices) {
                    for (j in i + 1 until mutableDatabaseClusters.size) {
                        val cluster1 = mutableDatabaseClusters[i]
                        val cluster2 = mutableDatabaseClusters[j]

                        if (cluster1.size + cluster2.size > maxClusterSize) continue

                        val distance = calculateDistance(
                            cluster1.centerLatitude, cluster1.centerLongitude,
                            cluster2.centerLatitude, cluster2.centerLongitude
                        )

                        if (distance < minDistance) {
                            minDistance = distance
                            mergeIndex1 = i
                            mergeIndex2 = j
                        }
                    }
                }

                if (mergeIndex1 == -1 || mergeIndex2 == -1) break

                val cluster1 = mutableDatabaseClusters[mergeIndex1]
                val cluster2 = mutableDatabaseClusters[mergeIndex2]

                cluster2.points.forEach { cluster1.addPoint(it) }
                mutableDatabaseClusters.removeAt(mergeIndex2)
            }

            mergedClusters.addAll(mutableDatabaseClusters)
        }

        return mergedClusters
    }

    private fun mergeClustersToTargetSize(
        clusters: MutableList<MarkerCluster>,
        zoomLevel: Double
    ): List<MarkerCluster> {
        val targetClusterCount = when {
            clusterAggressiveness >= 4.0f -> (clusters.size * 0.1).toInt().coerceAtLeast(1)
            clusterAggressiveness >= 3.0f -> (clusters.size * 0.2).toInt().coerceAtLeast(1)
            clusterAggressiveness >= 2.5f -> (clusters.size * 0.3).toInt().coerceAtLeast(1)
            clusterAggressiveness >= 2.0f -> (clusters.size * 0.5).toInt().coerceAtLeast(1)
            else -> (clusters.size * 0.7).toInt().coerceAtLeast(1)
        }

        while (clusters.size > targetClusterCount && clusters.size > 1) {
            var minDistance = Double.MAX_VALUE
            var mergeIndex1 = -1
            var mergeIndex2 = -1

            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val cluster1 = clusters[i]
                    val cluster2 = clusters[j]

                    if (cluster1.size + cluster2.size > maxClusterSize) continue

                    val distance = calculateDistance(
                        cluster1.centerLatitude, cluster1.centerLongitude,
                        cluster2.centerLatitude, cluster2.centerLongitude
                    )

                    if (distance < minDistance) {
                        minDistance = distance
                        mergeIndex1 = i
                        mergeIndex2 = j
                    }
                }
            }

            if (mergeIndex1 == -1 || mergeIndex2 == -1) break

            val cluster1 = clusters[mergeIndex1]
            val cluster2 = clusters[mergeIndex2]

            cluster2.points.forEach { cluster1.addPoint(it) }
            clusters.removeAt(mergeIndex2)
        }

        return clusters
    }

    private fun performSuperAggressiveClustering(
        points: List<NetworkPoint>,
        zoomLevel: Double
    ): List<MarkerCluster> {
        if (clusterAggressiveness < 3.5f) return performGridClustering(points, zoomLevel)

        val targetClusterCount = when {
            clusterAggressiveness >= 4.5f -> maxOf(1, points.size / 10000)
            clusterAggressiveness >= 4.0f -> maxOf(1, points.size / 5000)
            else -> maxOf(1, points.size / 2000)
        }

        val clusters = mutableListOf<MarkerCluster>()
        val remainingPoints = points.toMutableList()

        repeat(targetClusterCount) {
            if (remainingPoints.isEmpty()) return@repeat

            val seedPoint = remainingPoints.removeAt(0)
            val cluster = MarkerCluster(
                centerLatitude = seedPoint.latitude,
                centerLongitude = seedPoint.longitude
            )
            cluster.addPoint(seedPoint)

            val pointsToAdd = mutableListOf<NetworkPoint>()

            for (point in remainingPoints) {
                if (cluster.size >= maxClusterSize) break

                val distance = calculateDistance(
                    cluster.centerLatitude, cluster.centerLongitude,
                    point.latitude, point.longitude
                )

                val maxDistance = when {
                    zoomLevel <= 8 -> 50000.0
                    zoomLevel <= 10 -> 25000.0
                    zoomLevel <= 12 -> 10000.0
                    else -> 5000.0
                } * clusterAggressiveness

                if (distance <= maxDistance) {
                    pointsToAdd.add(point)
                }
            }

            pointsToAdd.forEach { point ->
                cluster.addPoint(point)
                remainingPoints.remove(point)
            }

            clusters.add(cluster)
        }

        remainingPoints.forEach { point ->
            val nearestCluster = clusters.minByOrNull { cluster ->
                calculateDistance(
                    cluster.centerLatitude, cluster.centerLongitude,
                    point.latitude, point.longitude
                )
            }

            if (nearestCluster != null && nearestCluster.size < maxClusterSize) {
                nearestCluster.addPoint(point)
            } else {
                clusters.add(MarkerCluster().apply { addPoint(point) })
            }
        }

        return clusters
    }

    private fun calculateGridSize(zoomLevel: Double): Double {
        val baseMultiplier = when {
            clusterAggressiveness >= 4.0f -> 50.0
            clusterAggressiveness >= 3.0f -> 25.0
            clusterAggressiveness >= 2.0f -> 12.0
            clusterAggressiveness >= 1.5f -> 6.0
            clusterAggressiveness >= 1.0f -> 3.0
            else -> 1.0
        }

        val zoomMultiplier = when {
            zoomLevel <= 6 -> 8.0
            zoomLevel <= 8 -> 6.0
            zoomLevel <= 10 -> 4.0
            zoomLevel <= 12 -> 2.0
            zoomLevel <= 14 -> 1.0
            zoomLevel <= 16 -> 0.5
            else -> 0.25
        }

        return (0.01 * baseMultiplier * zoomMultiplier).coerceIn(0.001, 50.0)
    }

    fun createAdaptiveClusters(
        points: List<NetworkPoint>,
        zoomLevel: Double,
        mapCenter: Pair<Double, Double>? = null
    ): List<MarkerCluster> {
        if (points.isEmpty()) return emptyList()

        if (canUseCachedClusters(zoomLevel, points)) {
            return cachedClusters
        }

        val processedPoints = if (forcePointSeparation) {
            spreadOverlappingPointsEfficient(points)
        } else {
            points
        }

        val clusters = if (preventClusterMerge) {
            processedPoints.map { point ->
                MarkerCluster().apply { addPoint(point) }
            }
        } else {
            performAdaptiveGridClusteringPerDatabase(processedPoints, zoomLevel, mapCenter)
        }

        updateCache(zoomLevel, points, clusters)
        return clusters
    }

    private fun performAdaptiveGridClusteringPerDatabase(
        points: List<NetworkPoint>,
        zoomLevel: Double,
        mapCenter: Pair<Double, Double>?
    ): List<MarkerCluster> {
        val baseGridSize = calculateGridSize(zoomLevel)
        val databaseGroups = points.groupBy { it.databaseId }
        val allClusters = mutableListOf<MarkerCluster>()

        databaseGroups.forEach { (databaseId, dbPoints) ->
            val cells = mutableMapOf<Pair<Int, Int>, GridCell>()

            dbPoints.forEach { point ->
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

            val databaseClusters = cells.values.map { cell ->
                MarkerCluster(
                    centerLatitude = cell.centerLatitude,
                    centerLongitude = cell.centerLongitude
                ).apply {
                    cell.points.forEach { point ->
                        addPoint(point)
                    }
                }
            }

            allClusters.addAll(databaseClusters)
        }

        return separateOverlappingClusters(allClusters, databaseGroups)
    }

    private fun applySeparationToClusters(clusters: List<MarkerCluster>): List<MarkerCluster> {
        if (clusters.isEmpty()) return clusters

        val positionGroups = mutableMapOf<String, MutableList<MarkerCluster>>()

        clusters.forEach { cluster ->
            val key = "${(cluster.centerLatitude * 100000).toInt()}_${(cluster.centerLongitude * 100000).toInt()}"
            positionGroups.getOrPut(key) { mutableListOf() }.add(cluster)
        }

        return positionGroups.flatMap { (_, groupClusters) ->
            when {
                groupClusters.size == 1 -> groupClusters
                groupClusters.size <= 16 -> {
                    val radius = 0.0001
                    val angleStep = 2 * Math.PI / groupClusters.size

                    groupClusters.mapIndexed { index, cluster ->
                        val angle = angleStep * index
                        val offsetLat = radius * sin(angle)
                        val offsetLon = radius * cos(angle)

                        MarkerCluster(
                            centerLatitude = cluster.centerLatitude + offsetLat,
                            centerLongitude = cluster.centerLongitude + offsetLon
                        ).apply {
                            cluster.points.forEach { point ->
                                addPoint(point.copy(
                                    offsetLatitude = point.offsetLatitude + offsetLat,
                                    offsetLongitude = point.offsetLongitude + offsetLon
                                ))
                            }
                        }
                    }
                }
                else -> {
                    val gridSize = 4
                    val radius = 0.00008

                    groupClusters.take(16).mapIndexed { index, cluster ->
                        val row = index / gridSize
                        val col = index % gridSize
                        val offsetLat = (row - gridSize / 2.0) * radius
                        val offsetLon = (col - gridSize / 2.0) * radius

                        MarkerCluster(
                            centerLatitude = cluster.centerLatitude + offsetLat,
                            centerLongitude = cluster.centerLongitude + offsetLon
                        ).apply {
                            cluster.points.forEach { point ->
                                addPoint(point.copy(
                                    offsetLatitude = point.offsetLatitude + offsetLat,
                                    offsetLongitude = point.offsetLongitude + offsetLon
                                ))
                            }
                        }
                    }
                }
            }
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

    fun clearAllCache() {
        lastZoomLevel = -1.0
        cachedClusters = emptyList()
        cachedPoints = emptyList()
        cachedBounds = null
        spatialIndex.clear()
    }

    fun forceRefresh() {
        clearAllCache()
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