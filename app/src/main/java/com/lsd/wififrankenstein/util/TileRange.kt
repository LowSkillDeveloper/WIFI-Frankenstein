package com.lsd.wififrankenstein.util

data class TileRange(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int
)

fun TileRange.toTileGroups(gridSize: Int): List<TileRange> {
    val groups = mutableListOf<TileRange>()
    for (gx in this.minX until this.maxX + 1 step gridSize) {
        for (gy in this.minY until this.maxY + 1 step gridSize) {
            val groupMaxX = minOf(gx + gridSize - 1, this.maxX)
            val groupMaxY = minOf(gy + gridSize - 1, this.maxY)
            groups.add(TileRange(gx, gy, groupMaxX, groupMaxY))
        }
    }
    return groups
}
