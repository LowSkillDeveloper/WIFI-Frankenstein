package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class ThreeWifiDevMapHelper(
    private val context: Context,
    private val serverUrl: String,
    private val apiReadKey: String
) {
    private val TAG = "ThreeWifiDevMapHelper"

    data class MapPoint(
        val id: String,
        val bssidDecimal: Long,
        val count: Int,
        val latitude: Double,
        val longitude: Double,
        val popupHtml: String? = null,
        val bounds: List<Pair<Double, Double>>? = null
    )

    suspend fun checkMapSupport(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val testUrl = "$serverUrl/fmap?tiles=0,0,1,1&zoom=1"
                val connection = URL(testUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                connection.disconnect()

                responseCode == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                Log.e(TAG, "Error checking map support", e)
                false
            }
        }
    }

    suspend fun getPointsInBoundingBox(
        boundingBox: BoundingBox,
        zoom: Double,
        maxPoints: Int = Int.MAX_VALUE
    ): List<MapPoint> {
        return withContext(Dispatchers.IO) {
            try {
                val tiles = calculateTiles(boundingBox, zoom.toInt())
                val tilesStr = "${tiles.minX},${tiles.minY},${tiles.maxX},${tiles.maxY}"

                val url = "$serverUrl/fmap?tiles=$tilesStr&zoom=${zoom.toInt()}"
                Log.d(TAG, "Fetching map data from: $url")

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()

                    parseMapResponse(response, maxPoints)
                } else {
                    Log.e(TAG, "HTTP error: ${connection.responseCode}")
                    connection.disconnect()
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching map points", e)
                emptyList()
            }
        }
    }

    private fun parseMapResponse(response: String, maxPoints: Int): List<MapPoint> {
        try {
            val parseServerPrefix = "parse_server(["
            val startIndex = response.indexOf(parseServerPrefix)
            if (startIndex == -1) {
                Log.e(TAG, "Invalid response format")
                return emptyList()
            }

            val jsonStart = startIndex + parseServerPrefix.length - 1
            val jsonEnd = response.lastIndexOf(",") + 1
            if (jsonEnd <= jsonStart) {
                Log.e(TAG, "Could not find JSON end")
                return emptyList()
            }

            val jsonStr = response.substring(jsonStart, jsonEnd - 1)
            val jsonArray = JSONArray(jsonStr)

            val points = mutableListOf<MapPoint>()

            for (i in 0 until minOf(jsonArray.length(), maxPoints)) {
                val item = jsonArray.getJSONObject(i)

                val point = MapPoint(
                    id = item.getString("q"),
                    bssidDecimal = item.optLong("b", 0L),
                    count = item.getInt("c"),
                    latitude = item.getDouble("o"),
                    longitude = item.getDouble("l"),
                    popupHtml = item.optString("h", null),
                    bounds = if (item.has("x")) {
                        val boundsArray = item.getJSONArray("x")
                        val boundsList = mutableListOf<Pair<Double, Double>>()
                        for (j in 0 until boundsArray.length()) {
                            val coord = boundsArray.getJSONArray(j)
                            boundsList.add(Pair(coord.getDouble(0), coord.getDouble(1)))
                        }
                        boundsList
                    } else null
                )

                points.add(point)
            }

            Log.d(TAG, "Parsed ${points.size} points from response")
            return points

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing map response", e)
            return emptyList()
        }
    }

    suspend fun getPointDetails(bssidDecimal: Long): Map<String, Any?>? {
        return withContext(Dispatchers.IO) {
            try {
                val bssidHex = String.format("%012X", bssidDecimal)
                val bssidMac = bssidHex.replace("(.{2})".toRegex(), "$1:").dropLast(1)

                val jsonObject = JSONObject().apply {
                    put("key", apiReadKey)
                    put("bssid", JSONArray().put(bssidMac))
                }

                val url = "$serverUrl/api/apiquery"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 15000

                connection.outputStream.use { it.write(jsonObject.toString().toByteArray()) }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()

                    val responseJson = JSONObject(response)
                    if (responseJson.getBoolean("result")) {
                        val data = responseJson.getJSONObject("data")
                        if (data.has(bssidMac.lowercase())) {
                            val networks = data.getJSONArray(bssidMac.lowercase())
                            if (networks.length() > 0) {
                                val network = networks.getJSONObject(0)
                                return@withContext mapOf(
                                    "time" to network.optString("time"),
                                    "bssid" to network.optString("bssid"),
                                    "essid" to network.optString("essid"),
                                    "sec" to network.optString("sec"),
                                    "key" to network.optString("key"),
                                    "wps" to network.optString("wps"),
                                    "lat" to network.optDouble("lat"),
                                    "lon" to network.optDouble("lon")
                                )
                            }
                        }
                    }
                    null
                } else {
                    Log.e(TAG, "HTTP error getting point details: ${connection.responseCode}")
                    connection.disconnect()
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting point details", e)
                null
            }
        }
    }

    private data class TileRange(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    )

    private fun calculateTiles(boundingBox: BoundingBox, zoom: Int): TileRange {
        val minX = lonToTileX(boundingBox.lonWest, zoom)
        val maxX = lonToTileX(boundingBox.lonEast, zoom)
        val minY = latToTileY(boundingBox.latNorth, zoom)
        val maxY = latToTileY(boundingBox.latSouth, zoom)

        return TileRange(
            minX = min(minX, maxX),
            minY = min(minY, maxY),
            maxX = max(minX, maxX),
            maxY = max(minY, maxY)
        )
    }

    private fun latToTileY(latitude: Double, zoom: Int): Int {
        val clippedLat = latitude.coerceIn(-85.05112878, 85.05112878)
        val sinLat = kotlin.math.sin(clippedLat * kotlin.math.PI / 180)
        val e = 0.0818191908426
        val y = 0.5 - ((kotlin.math.atanh(sinLat) - e * kotlin.math.atanh(e * sinLat)) / (2 * kotlin.math.PI))
        val sizeInTiles = 1 shl zoom
        return min(floor(y * sizeInTiles).toInt(), sizeInTiles - 1)
    }

    private fun lonToTileX(longitude: Double, zoom: Int): Int {
        val clippedLon = longitude.coerceIn(-180.0, 180.0)
        val x = (clippedLon + 180) / 360
        val sizeInTiles = 1 shl zoom
        return min(floor(x * sizeInTiles).toInt(), sizeInTiles - 1)
    }
}