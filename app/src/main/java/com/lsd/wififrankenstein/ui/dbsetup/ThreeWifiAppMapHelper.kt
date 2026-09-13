package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import com.lsd.wififrankenstein.network.ThreeWifiAppClient
import com.lsd.wififrankenstein.network.ThreeWifiAppException
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.BoundingBox

class ThreeWifiAppMapHelper(
    private val context: Context,
    serverUrl: String,
    private var jwtToken: String? = null
) : MapHelper {

    override val TAG = "ThreeWifiAppMapHelper"

    private val baseUrl: String = ThreeWifiAppClient.normalizeUrl(serverUrl)

    private val client: ThreeWifiAppClient
        get() = ThreeWifiAppClient.get(context, baseUrl)

    override suspend fun checkMapSupport(): Boolean = withContext(Dispatchers.IO) {
        try {
            client.getAppVersion()
            true
        } catch (e: Exception) {
            Log.d(TAG, "3wifi.app support check failed: ${e.message}")
            false
        }
    }

    override suspend fun getPointsInBoundingBox(
        boundingBox: BoundingBox,
        zoom: Double,
        maxPoints: Int
    ): List<MapPointData> = withContext(Dispatchers.IO) {
        try {
            val items = client.getNetworksByBounds(
                north = boundingBox.latNorth,
                south = boundingBox.latSouth,
                east = boundingBox.lonEast,
                west = boundingBox.lonWest,
                limit = minOf(MAX_POINTS_PER_REQUEST, maxPoints),
                token = jwtToken
            )
            parseMapItems(items)
        } catch (e: ThreeWifiAppException) {
            Log.w(TAG, "Map request rejected: ${e.message} (${e.trpcCode}/${e.httpStatus})")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching 3wifi.app map points", e)
            emptyList()
        }
    }

    private fun parseMapItems(items: JSONArray): List<MapPointData> {
        val points = ArrayList<MapPointData>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val bssid = item.optString("bssid", "")
            val pointId = item.optLong("id", 0L)

            val parsedBssid = bssid.replace(":", "").toLongOrNull(16)
            val bssidDecimal = parsedBssid ?: syntheticId(pointId)

            val rawLat = item.optDouble("latitude", 0.0)
            val rawLon = item.optDouble("longitude", 0.0)
            val latitude = if (rawLat != 0.0) rawLat else item.optDouble("lat", 0.0)
            val longitude = if (rawLon != 0.0) rawLon else item.optDouble("lng", 0.0)
            val password = item.optString("password", "")
            val essid = item.optString("ssid", item.optString("essid", ""))

            points.add(
                MapPointData(
                    id = item.optString("id", ""),
                    bssidDecimal = bssidDecimal,
                    count = 1,
                    latitude = latitude,
                    longitude = longitude,
                    essid = essid.ifEmpty { null },
                    password = password.ifEmpty { null },
                    securityType = item.optString("securityType", null)
                        ?: item.optString("security", null),
                    bssid = bssid
                )
            )
        }
        return points
    }

    private fun syntheticId(pointId: Long): Long = SYNTHETIC_ID_BASE + pointId

    override suspend fun getPointDetails(
        bssidDecimal: Long,
        remotePointId: Long?
    ): Map<String, Any?>? {
        val token = jwtToken ?: return null

        if (remotePointId != null) {
            return getPointDetailsById(remotePointId)
        }

        if (bssidDecimal >= SYNTHETIC_ID_BASE) {
            return getPointDetailsById(bssidDecimal - SYNTHETIC_ID_BASE)
        }

        return withContext(Dispatchers.IO) {
            try {
                val bssidMac = String.format("%012X", bssidDecimal)
                    .replace("(.{2})".toRegex(), "$1:")
                    .dropLast(1)
                val networks = client.searchNetworks(bssidMac, true, 1, token)
                val network = networks.optJSONObject(0) ?: return@withContext null
                network.toDetailMap()
            } catch (e: Exception) {
                Log.e(TAG, "Error searching 3wifi.app point details", e)
                null
            }
        }
    }

    suspend fun getPointDetailsById(id: Long): Map<String, Any?>? {
        val token = jwtToken ?: return null
        return withContext(Dispatchers.IO) {
            try {
                client.getAccessPointDetails(id, token)?.toDetailMap()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting 3wifi.app point details by id", e)
                null
            }
        }
    }

    private fun JSONObject.toDetailMap(): Map<String, Any?> {
        val lat = optDouble("latitude", 0.0)
        val lng = optDouble("longitude", 0.0)
        return mapOf(
            "time" to optString("time", ""),
            "bssid" to optString("bssid", ""),
            "essid" to optString("ssid", optString("essid", "")),
            "sec" to optString("securityType", optString("security", "")),
            "key" to optString("password", ""),
            "wps" to optString("wpsPin", ""),
            "lat" to if (lat != 0.0) lat else optDouble("lat", 0.0),
            "lon" to if (lng != 0.0) lng else optDouble("lng", 0.0),
            "manufacturer" to optString("manufacturer", "")
        )
    }

    fun setJwtToken(token: String?) {
        jwtToken = token
    }

    companion object {
        private const val MAX_POINTS_PER_REQUEST = 500
        private const val SYNTHETIC_ID_BASE = 1L shl 48
    }
}
