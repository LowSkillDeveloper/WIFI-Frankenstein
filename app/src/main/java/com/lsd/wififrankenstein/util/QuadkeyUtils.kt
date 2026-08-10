package com.lsd.wififrankenstein.util

import org.osmdroid.util.BoundingBox

object QuadkeyUtils {
    const val MAX_ZOOM = 23
    const val EARTH_ECCENTRICITY = 0.0818191908426

    fun latLonToQuadkey(latitude: Double, longitude: Double, zoom: Int = MAX_ZOOM): Long {
        if (zoom == 0) return 0L

        val clippedLat = latitude.coerceIn(-85.05112878, 85.05112878)
        val clippedLon = longitude.coerceIn(-180.0, 180.0)

        var tileX = lonToTileX(clippedLon, zoom)
        var tileY = latToTileY(clippedLat, zoom)

        var quadkey = 0L
        var bitPos = 0
        for (i in 0 until zoom) {
            val yBit = tileY.toLong() and 1L
            val xBit = tileX.toLong() and 1L

            quadkey = quadkey or ((xBit shl bitPos) or (yBit shl (bitPos + 1)))

            tileX = tileX shr 1
            tileY = tileY shr 1
            bitPos += 2
        }

        return quadkey
    }

    fun quadkeyToTileXY(quadkey: Long, zoom: Int = MAX_ZOOM): Pair<Int, Int> {
        var qk = quadkey
        var tileX = 0
        var tileY = 0

        for (i in 0 until zoom) {
            val xBit = (qk and 1L).toInt()
            val yBit = ((qk ushr 1) and 1L).toInt()

            tileX = (tileX shl 1) or xBit
            tileY = (tileY shl 1) or yBit

            qk = qk ushr 2
        }

        return Pair(tileX, tileY)
    }

    fun tileXYToLat(tileY: Int, zoom: Int): Double {
        val e = EARTH_ECCENTRICITY

        val y = Math.PI * (1.0 - 2.0 * tileY.toDouble() / (1 shl zoom))
        val sign = if (y < 0) -1.0 else 1.0
        var absY = kotlin.math.abs(y)

        var latN1 = atan(sinhh(absY))
        var latN = latN1

        for (iteration in 0 until 20) {
            val sinLat = Math.sin(latN)
            latN1 = asin(
                1.0 - (1.0 + sinLat) *
                        Math.pow((1.0 - e * sinLat) / (1.0 + e * sinLat), e) /
                        exp(2.0 * absY)
            )
            val diff = kotlin.math.abs(latN1 - latN)
            if (diff < 1e-7 || diff.isNaN()) break
            latN = latN1
        }

        return sign * latN1 * 180.0 / Math.PI
    }

    fun tileXYToLon(tileX: Int, zoom: Int): Double {
        return (Math.PI * (2.0 * tileX.toDouble() / (1 shl zoom) - 1.0)) * 180.0 / Math.PI
    }

    fun getQuadkeyBounds(minQk: Long, maxQk: Long): BoundingBox? {
        val (minTileX, minTileY) = quadkeyToTileXY(minQk, MAX_ZOOM)
        val (maxTileX, maxTileY) = quadkeyToTileXY(maxQk, MAX_ZOOM)

        val latNorth = tileXYToLat(minTileY, MAX_ZOOM)
        val lonWest = tileXYToLon(minTileX, MAX_ZOOM)
        val latSouth = tileXYToLat(maxTileY + 1, MAX_ZOOM)
        val lonEast = tileXYToLon(maxTileX + 1, MAX_ZOOM)

        return BoundingBox(latNorth, lonEast, latSouth, lonWest)
    }

    fun calculateVisibleTiles(bounds: BoundingBox, zoom: Int): TileRange {
        val minX = lonToTileX(bounds.lonWest, zoom)
        val maxX = lonToTileX(bounds.lonEast, zoom)
        val minY = latToTileY(bounds.latNorth, zoom)
        val maxY = latToTileY(bounds.latSouth, zoom)

        val size = 1 shl zoom
        val clampedMinX = minX.coerceIn(0, size - 1)
        val clampedMaxX = maxX.coerceIn(0, size - 1)
        val clampedMinY = minY.coerceIn(0, size - 1)
        val clampedMaxY = maxY.coerceIn(0, size - 1)

        return TileRange(
            minX = clampedMinX,
            minY = clampedMinY,
            maxX = clampedMaxX,
            maxY = clampedMaxY
        )
    }

    fun getTileRangeBounds(tileRange: TileRange, zoom: Int): BoundingBox {
        val latNorth = tileXYToLat(tileRange.minY, zoom)
        val lonWest = tileXYToLon(tileRange.minX, zoom)
        val latSouth = tileXYToLat(tileRange.maxY + 1, zoom)
        val lonEast = tileXYToLon(tileRange.maxX + 1, zoom)

        return BoundingBox(
            latNorth,
            kotlin.math.max(lonWest, lonEast),
            latSouth,
            kotlin.math.min(lonWest, lonEast)
        )
    }

    fun getQuadkeyRangeForTileRange(tileRange: TileRange, zoom: Int): Pair<Long, Long> {
        val minQk = latLonToQuadkey(
            tileXYToLat(tileRange.minY, zoom),
            tileXYToLon(tileRange.minX, zoom),
            zoom
        )
        val maxQk = latLonToQuadkey(
            tileXYToLat(tileRange.maxY + 1, zoom),
            tileXYToLon(tileRange.maxX + 1, zoom),
            zoom
        )

        return Pair(minOf(minQk, maxQk), maxOf(minQk, maxQk))
    }

    private fun clip(n: Double, minVal: Double, maxVal: Double): Double {
        return kotlin.math.min(kotlin.math.max(n, minVal), maxVal)
    }

    private fun latToTileY(latitude: Double, zoom: Int): Int {
        val clippedLat = clip(latitude, -85.05112878, 85.05112878)
        val sinLat = Math.sin(Math.toRadians(clippedLat))

        val y =
            0.5 - (atanh(sinLat) - EARTH_ECCENTRICITY * atanh(EARTH_ECCENTRICITY * sinLat)) / (2.0 * Math.PI)
        val sizeInTiles = 1 shl zoom

        return kotlin.math.min((y * sizeInTiles).toInt(), sizeInTiles - 1)
    }

    private fun lonToTileX(longitude: Double, zoom: Int): Int {
        val clippedLon = clip(longitude, -180.0, 180.0)
        val x = (clippedLon + 180.0) / 360.0
        val sizeInTiles = 1 shl zoom

        return kotlin.math.min((x * sizeInTiles).toInt(), sizeInTiles - 1)
    }

    private fun sinhh(x: Double): Double {
        return (Math.exp(x) - Math.exp(-x)) / 2.0
    }

    private fun atanh(x: Double): Double {
        return 0.5 * Math.log((1.0 + x) / (1.0 - x))
    }

    private fun exp(x: Double): Double {
        return Math.exp(x)
    }

    private fun atan(x: Double): Double {
        return kotlin.math.atan(x)
    }

    private fun asin(x: Double): Double {
        return kotlin.math.asin(x)
    }
}
