package com.lsd.wififrankenstein.ui.wifimap

import android.content.Context
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class OfflineMapManager(private val context: Context) {
    private val TAG = "OfflineMapManager"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        const val MAX_TILES = 6000
        const val DEFAULT_MIN_ZOOM = 10
        const val DEFAULT_MAX_ZOOM = 16
        private const val ZONES_JSON = "offline_zones.json"

        private fun lonToTileX(lon: Double, zoom: Int): Int {
            val n = 1 shl zoom
            return ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        }

        private fun latToTileY(lat: Double, zoom: Int): Int {
            val n = 1 shl zoom
            val latRad = Math.toRadians(lat)
            val y = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0
            return (y * n).toInt().coerceIn(0, n - 1)
        }
    }

    data class OfflineZone(
        val id: String,
        val name: String,
        val tileCount: Int,
        val sizeBytes: Long,
        val minZoom: Int,
        val maxZoom: Int,
        val north: Double,
        val south: Double,
        val east: Double,
        val west: Double,
        val createdAt: Long
    ) {
        fun toBoundingBox() = BoundingBox(north, east, south, west)
        fun toJson() = JSONObject().apply {
            put("id", id); put("name", name); put("tileCount", tileCount)
            put("sizeBytes", sizeBytes); put("minZoom", minZoom); put("maxZoom", maxZoom)
            put("north", north); put("south", south); put("east", east); put("west", west)
            put("createdAt", createdAt)
        }

        companion object {
            fun fromJson(j: JSONObject) = OfflineZone(
                j.getString("id"),
                j.getString("name"),
                j.getInt("tileCount"),
                j.getLong("sizeBytes"),
                j.getInt("minZoom"),
                j.getInt("maxZoom"),
                j.getDouble("north"),
                j.getDouble("south"),
                j.getDouble("east"),
                j.getDouble("west"),
                j.getLong("createdAt")
            )
        }
    }

    private fun getTileCacheDir(): File {
        val base = Configuration.getInstance().osmdroidTileCache
            ?: File(context.filesDir, "osmdroid/tiles")
        Log.d(TAG, "Tile cache dir: ${base.absolutePath}")
        return base
    }

    fun getZones(): List<OfflineZone> {
        val file = File(context.filesDir, ZONES_JSON)
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            val arr = JSONArray(text)
            (0 until arr.length()).map { OfflineZone.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading zones", e)
            emptyList()
        }
    }

    private fun saveZones(zones: List<OfflineZone>) {
        try {
            val file = File(context.filesDir, ZONES_JSON)
            val arr = JSONArray()
            zones.forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving zones", e)
        }
    }

    fun estimateTileCount(bounds: BoundingBox, minZoom: Int, maxZoom: Int): Int {
        var count = 0L
        for (zoom in minZoom..maxZoom) {
            val minX = lonToTileX(bounds.lonWest, zoom)
            val maxX = lonToTileX(bounds.lonEast, zoom)
            val minY = latToTileY(bounds.latNorth, zoom)
            val maxY = latToTileY(bounds.latSouth, zoom)
            val nx = (maxX - minX + 1).coerceAtLeast(1).toLong()
            val ny = (maxY - minY + 1).coerceAtLeast(1).toLong()
            count += nx * ny
        }
        return minOf(count, Int.MAX_VALUE.toLong()).toInt()
    }

    suspend fun downloadZone(
        bounds: BoundingBox,
        tileSourceName: String,
        name: String,
        minZoom: Int,
        maxZoom: Int,
        onProgress: (Float) -> Unit
    ): Result<OfflineZone> = withContext(Dispatchers.IO) {
        try {
            val id = UUID.randomUUID().toString()
            val tileDir = getTileCacheDir()
            val sourceDir = File(tileDir, tileSourceName)
            sourceDir.mkdirs()

            val estimated = estimateTileCount(bounds, minZoom, maxZoom)
            Log.d(
                TAG,
                "Starting download: name=$name, bounds=[${bounds.latNorth},${bounds.lonEast},${bounds.latSouth},${bounds.lonWest}], zooms=$minZoom-$maxZoom, estimated=$estimated tiles"
            )

            if (estimated > MAX_TILES) {
                Log.e(TAG, "Too many tiles: $estimated (max $MAX_TILES)")
                return@withContext Result.failure(
                    IllegalArgumentException("Too many tiles: $estimated (max $MAX_TILES)")
                )
            }
            if (estimated == 0) {
                Log.e(TAG, "No tiles to download in the given bounds")
                return@withContext Result.failure(IllegalArgumentException("No tiles to download"))
            }

            var totalDownloaded = 0
            var totalSkippedExisting = 0
            var totalFailed = 0
            var totalSize = 0L
            var totalAttempted = 0

            for (zoom in minZoom..maxZoom) {
                val minX = lonToTileX(bounds.lonWest, zoom)
                val maxX = lonToTileX(bounds.lonEast, zoom)
                val minY = latToTileY(bounds.latNorth, zoom)
                val maxY = latToTileY(bounds.latSouth, zoom)
                Log.d(
                    TAG,
                    "Zoom $zoom: tile range x=$minX..$maxX, y=$minY..$maxY (${(maxX - minX + 1).toLong() * (maxY - minY + 1)} tiles)"
                )

                for (x in minX..maxX) {
                    for (y in minY..maxY) {
                        val tileFile = File(sourceDir, "$zoom/$x/$y.png")
                        if (tileFile.exists()) {
                            totalSkippedExisting++
                            totalDownloaded++
                            totalSize += tileFile.length()
                            Log.d(
                                TAG,
                                "Tile already cached: $zoom/$x/$y.png (${tileFile.length()} bytes)"
                            )
                        } else {
                            val url = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
                            try {
                                val request = Request.Builder().url(url)
                                    .header("User-Agent", "WiFiFrankenstein/1.0")
                                    .build()
                                val response = client.newCall(request).execute()
                                val code = response.code
                                if (response.isSuccessful) {
                                    val data = response.body?.bytes()
                                    if (data != null && data.isNotEmpty()) {
                                        tileFile.parentFile?.mkdirs()
                                        tileFile.writeBytes(data)
                                        totalDownloaded++
                                        totalSize += data.size
                                    } else {
                                        totalFailed++
                                        Log.w(TAG, "Empty body: $url (HTTP $code)")
                                    }
                                } else {
                                    totalFailed++
                                    Log.w(TAG, "HTTP $code for $url")
                                }
                                response.close()
                            } catch (e: Exception) {
                                totalFailed++
                                Log.e(TAG, "Error: $url — ${e.message}")
                            }
                            kotlinx.coroutines.delay(100)
                        }
                        totalAttempted++
                        onProgress(totalAttempted.toFloat() / estimated)
                    }
                }
            }

            Log.d(
                TAG,
                "Download complete: downloaded=$totalDownloaded, skipped_existing=$totalSkippedExisting, failed=$totalFailed, total_size=${totalSize / 1024}KB (attempted=$totalAttempted, estimated=$estimated)"
            )

            val zone = OfflineZone(
                id = id,
                name = name,
                tileCount = totalDownloaded,
                sizeBytes = totalSize,
                minZoom = minZoom,
                maxZoom = maxZoom,
                north = bounds.latNorth,
                south = bounds.latSouth,
                east = bounds.lonEast,
                west = bounds.lonWest,
                createdAt = System.currentTimeMillis()
            )

            val zones = getZones().toMutableList()
            zones.add(0, zone)
            saveZones(zones)

            Result.success(zone)
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            Result.failure(e)
        }
    }

    fun deleteZone(id: String): Boolean {
        return try {
            val zones = getZones().toMutableList()
            val zone = zones.find { it.id == id } ?: return false
            zones.remove(zone)
            saveZones(zones)

            val tileDir = getTileCacheDir()
            val sourceDir = File(tileDir, "Mapnik")
            for (zoom in zone.minZoom..zone.maxZoom) {
                val minX = lonToTileX(zone.west, zoom)
                val maxX = lonToTileX(zone.east, zoom)
                val minY = latToTileY(zone.north, zoom)
                val maxY = latToTileY(zone.south, zoom)
                for (x in minX..maxX) {
                    for (y in minY..maxY) {
                        val tileFile = File(sourceDir, "$zoom/$x/$y.png")
                        if (tileFile.exists()) tileFile.delete()
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting zone", e)
            false
        }
    }

    fun getZoneSizeFormatted(zone: OfflineZone): String {
        return when {
            zone.sizeBytes < 1024 -> "${zone.sizeBytes} B"
            zone.sizeBytes < 1024 * 1024 -> "${zone.sizeBytes / 1024} KB"
            else -> "%.1f MB".format(java.util.Locale.US, zone.sizeBytes / (1024.0 * 1024.0))
        }
    }
}
