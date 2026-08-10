package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.SslHelper
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
    serverUrl: String,
    private val apiReadKey: String
) : MapHelper {
    override val TAG = "ThreeWifiDevMapHelper"

    private val normalizedUrl: String = run {
        var url = serverUrl
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        url.trimEnd('/')
    }

    override suspend fun checkMapSupport(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val testUrl = "$normalizedUrl/fmap?tiles=0,0,1,1&zoom=1"
                val connection = URL(testUrl).openConnection() as HttpURLConnection
                SslHelper.configure(connection)
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

    override suspend fun getPointsInBoundingBox(
        boundingBox: BoundingBox,
        zoom: Double,
        maxPoints: Int
    ): List<MapPointData> {
        return withContext(Dispatchers.IO) {
            try {
                val tiles = calculateTiles(boundingBox, zoom.toInt())
                val tilesStr = "${tiles.minX},${tiles.minY},${tiles.maxX},${tiles.maxY}"

                val url = "$normalizedUrl/fmap?tiles=$tilesStr&zoom=${zoom.toInt()}"
                Log.d(TAG, "Fetching map data from: $url")

                val connection = URL(url).openConnection() as HttpURLConnection
                SslHelper.configure(connection)
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

    private fun parseMapResponse(response: String, maxPoints: Int): List<MapPointData> {
        try {
            val jsonArray: JSONArray
            val trimmed = response.trim()

            if (trimmed.startsWith("[")) {
                jsonArray = JSONArray(trimmed)
            } else {
                val prefix = "parse_server(["
                val startIndex = trimmed.indexOf(prefix)
                if (startIndex != -1) {
                    val jsonStart = startIndex + prefix.length - 1
                    val jsonEnd = trimmed.lastIndexOf(",") + 1
                    if (jsonEnd > jsonStart) {
                        jsonArray = JSONArray(trimmed.substring(jsonStart, jsonEnd - 1))
                    } else {
                        Log.e(TAG, "Could not find JSON end in parse_server format")
                        return emptyList()
                    }
                } else {
                    Log.e(TAG, "Invalid response format: not a JSON array or parse_server wrapper")
                    return emptyList()
                }
            }

            val points = mutableListOf<MapPointData>()

            for (i in 0 until minOf(jsonArray.length(), maxPoints)) {
                val item = jsonArray.getJSONObject(i)

                val point = MapPointData(
                    id = item.optString("q", ""),
                    bssidDecimal = item.optLong("b", 0L),
                    count = item.getInt("c"),
                    latitude = item.getDouble("o"),
                    longitude = item.getDouble("l"),
                    popupHtml = item.optString("h", null)
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

    override suspend fun getPointDetails(bssidDecimal: Long): Map<String, Any?>? {
        return withContext(Dispatchers.IO) {
            try {
                val bssidHex = String.format("%012X", bssidDecimal)
                val bssidMac = bssidHex.replace("(.{2})".toRegex(), "$1:").dropLast(1)

                val jsonObject = JSONObject().apply {
                    put("key", apiReadKey)
                    put("bssid", JSONArray().put(bssidMac))
                }

                val url = "$normalizedUrl/api/apiquery"
                val connection = URL(url).openConnection() as HttpURLConnection
                SslHelper.configure(connection)
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
                        if (data.has(bssidMac)) {
                            val networks = data.getJSONArray(bssidMac)
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
        val y =
            0.5 - ((kotlin.math.atanh(sinLat) - e * kotlin.math.atanh(e * sinLat)) / (2 * kotlin.math.PI))
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
