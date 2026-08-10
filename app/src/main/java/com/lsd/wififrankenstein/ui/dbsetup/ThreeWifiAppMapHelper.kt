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
import java.net.URLEncoder

class ThreeWifiAppMapHelper(
    private val context: Context,
    serverUrl: String,
    private var jwtToken: String? = null
) : MapHelper {
    override val TAG = "ThreeWifiAppMapHelper"

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
                val params = JSONObject().apply {
                    put("json", JSONObject())
                }
                val encoded = URLEncoder.encode(params.toString(), "UTF-8")
                val testUrl = "$normalizedUrl/trpc/getAppVersion?input=$encoded"
                val connection = URL(testUrl).openConnection() as HttpURLConnection
                SslHelper.configure(connection)
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                connection.disconnect()

                responseCode == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                Log.e(TAG, "Error checking 3wifi.app support", e)
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
                val params = JSONObject().apply {
                    put("json", JSONObject().apply {
                        put("north", boundingBox.latNorth)
                        put("south", boundingBox.latSouth)
                        put("east", boundingBox.lonEast)
                        put("west", boundingBox.lonWest)
                        put("limit", minOf(500, maxPoints))
                    })
                }
                val encoded = URLEncoder.encode(params.toString(), "UTF-8")
                val url = "$normalizedUrl/trpc/getNetworksByBounds?input=$encoded"
                Log.d(TAG, "Fetching map data from: $url")

                val connection = URL(url).openConnection() as HttpURLConnection
                SslHelper.configure(connection)
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 15000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    parseMapResponse(response)
                } else {
                    Log.e(TAG, "HTTP error: ${connection.responseCode}")
                    connection.disconnect()
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching 3wifi.app map points", e)
                emptyList()
            }
        }
    }

    private fun parseMapResponse(response: String): List<MapPointData> {
        try {
            val json = JSONObject(response)
            val unwrapped = parseTrpcResponse(json) ?: return emptyList()

            val items = when (unwrapped) {
                is JSONArray -> unwrapped
                is JSONObject -> JSONArray().put(unwrapped)
                else -> return emptyList()
            }

            val points = mutableListOf<MapPointData>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val bssid = item.optString("bssid", "")
                val pointId = item.optLong("id", 0L)
                val bssidDecimal = if (bssid.isNotEmpty()) {
                    bssid.replace(":", "").toLongOrNull(16) ?: syntheticId(pointId)
                } else {
                    syntheticId(pointId)
                }

                val rawLat = item.optDouble("latitude", 0.0)
                val rawLon = item.optDouble("longitude", 0.0)
                val latitude = if (rawLat != 0.0) rawLat else item.optDouble("lat", 0.0)
                val longitude = if (rawLon != 0.0) rawLon else item.optDouble("lng", 0.0)

                val password = item.optString("password", "")
                val essid = item.optString("ssid", "")

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

            Log.d(TAG, "Parsed ${points.size} points from 3wifi.app response")
            return points
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing 3wifi.app map response", e)
            return emptyList()
        }
    }

    private fun syntheticId(pointId: Long): Long {
        return (1L shl 48) + pointId
    }

    override suspend fun getPointDetails(bssidDecimal: Long): Map<String, Any?>? {
        if (jwtToken == null) {
            Log.d(TAG, "No JWT token — skipping point details request")
            return null
        }

        val syntheticIdBase = 1L shl 48
        if (bssidDecimal >= syntheticIdBase) {
            val realId = (bssidDecimal - syntheticIdBase).toInt()
            return getPointDetailsById(realId)
        }

        return withContext(Dispatchers.IO) {
            try {
                val bssidHex = String.format("%012X", bssidDecimal)
                val bssidMac = bssidHex.replace("(.{2})".toRegex(), "$1:").dropLast(1)

                val innerParams = JSONObject().apply {
                    put("bssid", bssidMac)
                    put("token", jwtToken)
                }
                val params = JSONObject().apply {
                    put("json", innerParams)
                }
                val encoded = URLEncoder.encode(params.toString(), "UTF-8")
                val url = "$normalizedUrl/trpc/searchNetworks?input=$encoded"

                val connection = URL(url).openConnection() as HttpURLConnection
                SslHelper.configure(connection)
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $jwtToken")
                connection.connectTimeout = 10000
                connection.readTimeout = 15000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    val json = JSONObject(response)
                    val unwrapped = parseTrpcResponse(json)
                    if (unwrapped is JSONObject) {
                        val networks = unwrapped.optJSONArray("networks")
                        if (networks != null && networks.length() > 0) {
                            val network = networks.getJSONObject(0)
                            return@withContext mapOf(
                                "time" to network.optString("time", ""),
                                "bssid" to network.optString("bssid", ""),
                                "essid" to network.optString(
                                    "ssid",
                                    network.optString("essid", "")
                                ),
                                "sec" to network.optString(
                                    "securityType",
                                    network.optString("security", "")
                                ),
                                "key" to network.optString("password", ""),
                                "wps" to network.optString("wpsPin", ""),
                                "lat" to network.optDouble("latitude", 0.0),
                                "lon" to network.optDouble("longitude", 0.0)
                            )
                        }
                    }
                } else {
                    Log.w(TAG, "HTTP ${connection.responseCode} for searchNetworks")
                }
                connection.disconnect()

                null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting 3wifi.app point details", e)
                null
            }
        }
    }

    suspend fun getPointDetailsById(id: Int): Map<String, Any?>? {
        if (jwtToken == null) {
            Log.d(TAG, "No JWT token — skipping point details by id request")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val innerParams = JSONObject().apply {
                    put("id", id)
                    put("token", jwtToken)
                }
                val params = JSONObject().apply {
                    put("json", innerParams)
                }
                val encoded = URLEncoder.encode(params.toString(), "UTF-8")
                val url = "$normalizedUrl/trpc/getAccessPointDetails?input=$encoded"

                val connection = URL(url).openConnection() as HttpURLConnection
                SslHelper.configure(connection)
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $jwtToken")
                connection.connectTimeout = 10000
                connection.readTimeout = 15000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()

                    val json = JSONObject(response)
                    val pointObject = parseTrpcResponse(json)

                    if (pointObject is JSONObject) {
                        val lat = pointObject.optDouble("latitude", 0.0)
                        val lng = pointObject.optDouble("longitude", 0.0)
                        return@withContext mapOf(
                            "time" to pointObject.optString("time", ""),
                            "bssid" to pointObject.optString("bssid", ""),
                            "essid" to pointObject.optString(
                                "ssid",
                                pointObject.optString("essid", "")
                            ),
                            "sec" to pointObject.optString(
                                "securityType",
                                pointObject.optString("security", "")
                            ),
                            "key" to pointObject.optString("password", ""),
                            "wps" to pointObject.optString("wpsPin", ""),
                            "lat" to if (lat != 0.0) lat else pointObject.optDouble("lat", 0.0),
                            "lon" to if (lng != 0.0) lng else pointObject.optDouble("lng", 0.0),
                            "manufacturer" to pointObject.optString("manufacturer", "")
                        )
                    }
                    null
                } else {
                    Log.w(TAG, "HTTP ${connection.responseCode} for getAccessPointDetails")
                    connection.disconnect()
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting 3wifi.app point details by id", e)
                null
            }
        }
    }

    suspend fun login(usernameOrEmail: String, password: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val innerParams = JSONObject().apply {
                    put("usernameOrEmail", usernameOrEmail)
                    put("password", password)
                }
                val body = JSONObject().apply {
                    put("json", innerParams)
                }

                val url = "$normalizedUrl/trpc/login"
                val connection = URL(url).openConnection() as HttpURLConnection
                SslHelper.configure(connection)
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 15000

                connection.outputStream.use { it.write(body.toString().toByteArray()) }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    connection.disconnect()

                    val json = JSONObject(response)
                    val unwrapped = parseTrpcResponse(json)
                    if (unwrapped is JSONObject) {
                        val token = unwrapped.optString("token", null)
                        if (token != null) {
                            jwtToken = token
                            Log.d(TAG, "Login successful, JWT token obtained")
                            return@withContext true
                        }
                    }
                }
                connection.disconnect()
                Log.e(TAG, "Login failed")
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error during login", e)
                false
            }
        }
    }

    fun getJwtToken(): String? = jwtToken

    fun setJwtToken(token: String?) {
        jwtToken = token
    }

    private fun parseTrpcResponse(json: JSONObject): Any? {
        val result = json.optJSONObject("result")
        val data = result?.optJSONObject("data")
        if (data != null && data.has("json")) {
            return data.get("json")
        }
        if (json.has("json")) {
            return json.get("json")
        }
        return json
    }
}
