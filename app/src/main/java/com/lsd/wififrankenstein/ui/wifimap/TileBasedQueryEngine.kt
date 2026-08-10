package com.lsd.wififrankenstein.ui.wifimap

import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.QuadkeyUtils
import com.lsd.wififrankenstein.util.TileRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox

object TileBasedQueryEngine {
    private const val TAG = "TileBasedQueryEngine"
    private const val MIN_ZOOM_FOR_TILES = 11

    data class TileQueryResult(
        val points: List<ClusteredMapPoint>,
        val hadError: Boolean
    )

    data class VisibleArea(
        val tileRange: TileRange,
        val expandedBounds: BoundingBox
    )

    fun calculateVisibleTiles(bounds: BoundingBox, zoom: Int): TileRange? {
        if (zoom < MIN_ZOOM_FOR_TILES) {
            Log.d(
                TAG,
                "Zoom $zoom below MIN_ZOOM_FOR_TILES ($MIN_ZOOM_FOR_TILES), skipping tile query"
            )
            return null
        }

        val tileRange = QuadkeyUtils.calculateVisibleTiles(bounds, zoom)
        Log.d(
            TAG,
            "Tile query: zoom=$zoom, range=($tileRange.minX,$tileRange.minY)-($tileRange.maxX,$tileRange.maxY)"
        )
        return tileRange
    }

    fun calculateVisibleTilesWithPadding(
        bounds: BoundingBox,
        zoom: Int,
        paddingFactor: Double = 0.5
    ): VisibleArea? {
        if (zoom < MIN_ZOOM_FOR_TILES) {
            Log.d(
                TAG,
                "Zoom $zoom below MIN_ZOOM_FOR_TILES ($MIN_ZOOM_FOR_TILES), skipping tile query with padding"
            )
            return null
        }

        val latPadding = (bounds.latNorth - bounds.latSouth) * paddingFactor
        val lonPadding = (bounds.lonEast - bounds.lonWest) * paddingFactor

        val maxLat = 85.05112878
        val minLon = -180.0
        val maxLon = 180.0
        val expandedBounds = BoundingBox(
            (bounds.latNorth + latPadding).coerceAtMost(maxLat),
            (bounds.lonEast + lonPadding).coerceAtMost(maxLon),
            (bounds.latSouth - latPadding).coerceAtLeast(-maxLat),
            (bounds.lonWest - lonPadding).coerceAtLeast(minLon)
        )




        Log.d(
            TAG,
            "Tile query with padding: zoom=$zoom, padding=$paddingFactor, expanded bounds=[$expandedBounds]"
        )

        val tileRange = QuadkeyUtils.calculateVisibleTiles(expandedBounds, zoom)
        Log.d(
            TAG,
            "Tile query with padding result: zoom=$zoom, range=(${tileRange.minX},${tileRange.minY})-(${tileRange.maxX},${tileRange.maxY})"
        )
        return VisibleArea(tileRange, expandedBounds)
    }

    suspend fun queryTileRange(
        tileRange: TileRange,
        helper: SQLite3WiFiHelper,
        zoom: Int,
        scatterMode: Boolean
    ): TileQueryResult = withContext(Dispatchers.IO) {
        try {
            val points = helper.getClusteredPointsByTileRange(
                tileRange.minX, tileRange.minY,
                tileRange.maxX, tileRange.maxY,
                zoom, scatterMode
            )
            Log.d(TAG, "Tile query result: ${points.size} points")
            TileQueryResult(points, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error in tile query", e)
            TileQueryResult(emptyList(), true)
        }
    }

    suspend fun queryExternalIndexTileRange(
        tileRange: TileRange,
        manager: ExternalIndexManager,
        dbId: String,
        dbPath: String,
        tableName: String,
        columnMap: Map<String, String>?,
        zoom: Int,
        scatterMode: Boolean
    ): TileQueryResult = withContext(Dispatchers.IO) {
        try {
            val points = manager.getClusteredPointsByTileRange(
                dbId, dbPath, tableName, columnMap,
                tileRange.minX, tileRange.minY,
                tileRange.maxX, tileRange.maxY,
                zoom, scatterMode
            ) ?: emptyList()
            Log.d(TAG, "External index tile query result: ${points.size} points")
            TileQueryResult(points, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error in external index tile query", e)
            TileQueryResult(emptyList(), true)
        }
    }

    fun mergeResults(results: List<TileQueryResult>): TileQueryResult {
        val allPoints = mutableListOf<ClusteredMapPoint>()
        var hadError = false

        for (result in results) {
            if (result.hadError) hadError = true
            allPoints.addAll(result.points)
        }

        if (hadError) {
            Log.w(TAG, "Merged ${allPoints.size} points with some errors")
        }

        return TileQueryResult(allPoints, hadError)
    }
}
