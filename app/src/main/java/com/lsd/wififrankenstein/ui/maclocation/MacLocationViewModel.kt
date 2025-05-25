package com.lsd.wififrankenstein.ui.maclocation

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.models.AppleWLoc
import com.lsd.wififrankenstein.models.WifiDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.protobuf.ProtoBuf
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class MacLocationViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("mac_location_prefs", Context.MODE_PRIVATE)

    data class LocationResult(
        val module: String,
        val bssid: String? = null,
        val ssid: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val error: String? = null,
        val vendor: String? = null
    )

    data class ApiKeys(
        val wigleApi: String,
        val googleApi: String,
        val combainApi: String
    )

    data class SearchType(
        val type: String,
        val query: String
    )

    private val _searchResults = MutableLiveData<List<LocationResult>>()
    private val _newResult = MutableLiveData<LocationResult>()
    private val _isLoading = MutableLiveData<Boolean>()
    private val _error = MutableLiveData<String>()
    private val _savedApiKeys = MutableLiveData<ApiKeys>()
    private val _logMessages = MutableLiveData<String>()

    val searchResults: LiveData<List<LocationResult>> = _searchResults
    val newResult: LiveData<LocationResult> = _newResult
    val isLoading: LiveData<Boolean> = _isLoading
    val error: LiveData<String> = _error
    val savedApiKeys: LiveData<ApiKeys> = _savedApiKeys
    val logMessages: LiveData<String> = _logMessages

    private val currentResults = mutableListOf<LocationResult>()

    init {
        loadSavedApiKeys()
    }

    private fun log(message: String) {
        val currentLog = _logMessages.value ?: ""
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _logMessages.postValue("$currentLog\n[$timestamp] $message")
        Log.d(TAG, message)
    }

    private fun loadSavedApiKeys() {
        val wigleApi = prefs.getString(KEY_WIGLE_API, "") ?: ""
        val googleApi = prefs.getString(KEY_GOOGLE_API, "") ?: ""
        val combainApi = prefs.getString(KEY_COMBAIN_API, "") ?: ""
        _savedApiKeys.value = ApiKeys(wigleApi, googleApi, combainApi)
    }

    fun saveApiKeys(wigleApi: String, googleApi: String, combainApi: String) {
        log("Saving API keys...")
        prefs.edit().apply {
            putString(KEY_WIGLE_API, wigleApi)
            putString(KEY_GOOGLE_API, googleApi)
            putString(KEY_COMBAIN_API, combainApi)
            apply()
        }
        _savedApiKeys.value = ApiKeys(wigleApi, googleApi, combainApi)
        log("✅ API keys saved successfully")
    }

    fun search(searchType: SearchType) {
        currentResults.clear()
        _searchResults.value = emptyList()

        when (searchType.type) {
            "MAC" -> searchByMac(searchType.query)
            "SSID" -> searchBySSID(searchType.query)
        }
    }

    private fun addResult(result: LocationResult) {
        if (result.latitude != null && result.longitude != null) {
            currentResults.add(result)
            _searchResults.postValue(currentResults.toList())
            _newResult.postValue(result)
        }
    }

    private fun searchByMac(mac: String) {
        if (!isValidMacAddress(mac)) {
            log("❌ Invalid MAC address format: $mac")
            _error.value = "Invalid MAC address format"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            var completedTasks = 0
            val totalTasks = 6

            fun checkCompletion() {
                completedTasks++
                if (completedTasks >= totalTasks) {
                    _isLoading.value = false
                    log("Search completed. Total results: ${currentResults.size}")
                    if (currentResults.isEmpty()) {
                        log("❌ No results found")
                        _error.value = "No results found"
                    }
                }
            }

            try {
                log("Starting search for MAC: $mac")

                launch {
                    searchApple(mac)
                    checkCompletion()
                }
                launch {
                    searchMylnikov(mac)
                    checkCompletion()
                }

                if (_savedApiKeys.value?.googleApi?.isNotBlank() == true) {
                    launch {
                        searchGoogle(mac)
                        checkCompletion()
                    }
                } else {
                    checkCompletion()
                }

                if (_savedApiKeys.value?.combainApi?.isNotBlank() == true) {
                    launch {
                        searchCombain(mac)
                        checkCompletion()
                    }
                } else {
                    checkCompletion()
                }

                if (_savedApiKeys.value?.wigleApi?.isNotBlank() == true) {
                    launch {
                        searchWigle(mac, true)
                        checkCompletion()
                    }
                } else {
                    checkCompletion()
                }

                launch {
                    searchWifiDB(mac, true)
                    checkCompletion()
                }

            } catch (e: Exception) {
                log("❌ Search error: ${e.message}")
                Log.e(TAG, "Search error", e)
                _error.value = e.message
                _isLoading.value = false
            }
        }
    }

    private fun searchBySSID(ssid: String) {
        viewModelScope.launch {
            _isLoading.value = true
            var completedTasks = 0
            val totalTasks = 4

            fun checkCompletion() {
                completedTasks++
                if (completedTasks >= totalTasks) {
                    _isLoading.value = false
                    log("Search completed. Total results: ${currentResults.size}")
                    if (currentResults.isEmpty()) {
                        log("❌ No results found")
                        _error.value = "No results found"
                    }
                }
            }

            try {
                log("Starting search for SSID: $ssid")

                launch {
                    searchFreifunkCarte(ssid)
                    checkCompletion()
                }
                launch {
                    searchOpenWifiMap(ssid)
                    checkCompletion()
                }

                if (_savedApiKeys.value?.wigleApi?.isNotBlank() == true) {
                    launch {
                        searchWigle(ssid, false)
                        checkCompletion()
                    }
                } else {
                    checkCompletion()
                }

                launch {
                    searchWifiDB(ssid, false)
                    checkCompletion()
                }

            } catch (e: Exception) {
                log("❌ Search error: ${e.message}")
                Log.e(TAG, "Search error", e)
                _error.value = e.message
                _isLoading.value = false
            }
        }
    }

    private suspend fun searchWigle(query: String, isMac: Boolean) =
        withContext(Dispatchers.IO) {
            try {
                val searchType = if (isMac) "MAC" else "SSID"
                log("Starting Wigle search by $searchType: $query")

                val apiKey = _savedApiKeys.value?.wigleApi ?: return@withContext
                val params = if (isMac) "netid=$query" else "ssid=$query"

                val url = URL("https://api.wigle.net/api/v2/network/search?$params")

                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Basic $apiKey")
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonResponse = JSONObject(response)

                if (jsonResponse.getBoolean("success")) {
                    val results = jsonResponse.getJSONArray("results")
                    if (results.length() > 0) {
                        val result = results.getJSONObject(0)
                        val locationResult = LocationResult(
                            module = "wigle",
                            bssid = if (isMac) query else result.getString("netid"),
                            ssid = if (!isMac) query else result.getString("ssid"),
                            latitude = result.getDouble("trilat"),
                            longitude = result.getDouble("trilong")
                        )
                        log("✅ Wigle search successful. Found location: ${locationResult.latitude}, ${locationResult.longitude}")
                        addResult(locationResult)
                    } else {
                        log("ℹ️ Wigle search completed but no results found")
                    }
                } else {
                    log("⚠️ Wigle search failed: ${jsonResponse.optString("message", "Unknown error")}")
                }
            } catch (e: Exception) {
                log("❌ Wigle search error: ${e.message}")
                Log.e(TAG, "Wigle search error", e)
            }
        }

    private suspend fun searchMylnikov(macAddress: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting Mylnikov search for MAC: $macAddress")

                val url = URL("https://api.mylnikov.org/geolocation/wifi?v=1.1&data=open&bssid=$macAddress")

                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                    setRequestProperty("Content-Type", "application/json")
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonResponse = JSONObject(response)

                if (jsonResponse.getInt("result") == 200) {
                    val data = jsonResponse.getJSONObject("data")
                    val locationResult = LocationResult(
                        module = "mylnikov",
                        bssid = macAddress,
                        latitude = data.getDouble("lat"),
                        longitude = data.getDouble("lon")
                    )
                    log("✅ Mylnikov search successful. Found location: ${locationResult.latitude}, ${locationResult.longitude}")
                    addResult(locationResult)
                } else {
                    log("ℹ️ Mylnikov search completed but no results found: ${jsonResponse.optString("desc")}")
                }
            } catch (e: Exception) {
                log("❌ Mylnikov search error: ${e.message}")
                Log.e(TAG, "Mylnikov search error", e)
            }
        }

    private suspend fun searchApple(macAddress: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting Apple search for MAC: $macAddress")

                val appleWLoc = AppleWLoc(
                    unknown_value0 = 0,
                    wifi_devices = listOf(
                        WifiDevice(bssid = macAddress)
                    ),
                    unknown_value1 = 0,
                    return_single_result = 1
                )

                val serializedRequest = ProtoBuf.encodeToByteArray(AppleWLoc.serializer(), appleWLoc)
                val length = serializedRequest.size

                val fullRequest = byteArrayOf(
                    0x00, 0x01, 0x00, 0x05
                ) + "en_US".toByteArray() + byteArrayOf(
                    0x00, 0x13
                ) + "com.apple.locationd".toByteArray() + byteArrayOf(
                    0x00, 0x0a
                ) + "8.1.12B411".toByteArray() + byteArrayOf(
                    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
                    length.toByte()
                ) + serializedRequest

                val url = URL("https://gs-loc.apple.com/clls/wloc")

                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("User-Agent", "locationd/1753.17 CFNetwork/889.9 Darwin/17.2.0")
                }

                connection.outputStream.use { it.write(fullRequest) }

                val response = connection.inputStream.use { stream ->
                    stream.skip(10)
                    stream.readBytes()
                }

                try {
                    val appleResponse = ProtoBuf.decodeFromByteArray(AppleWLoc.serializer(), response)

                    for (device in appleResponse.wifi_devices) {
                        device.location?.let { location ->
                            location.latitude?.let { lat ->
                                location.longitude?.let { lon ->
                                    if (lat != 18000000000L) {
                                        val latitude = lat.toDouble() * 1e-8
                                        val longitude = lon.toDouble() * 1e-8

                                        log("✅ Apple search successful. Found location: $latitude, $longitude")
                                        addResult(LocationResult(
                                            module = "apple",
                                            bssid = macAddress,
                                            latitude = latitude,
                                            longitude = longitude
                                        ))
                                        return@withContext
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    log("DEBUG: Failed to decode protobuf: ${e.message}")
                    throw e
                }
                log("ℹ️ Apple search completed but no valid results found")

            } catch (e: Exception) {
                log("❌ Apple search error: ${e.message}")
                Log.e(TAG, "Apple search error", e)
            }
        }

    private suspend fun searchGoogle(macAddress: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting Google search for MAC: $macAddress")

                val apiKey = _savedApiKeys.value?.googleApi ?: return@withContext
                val url = URL("https://www.googleapis.com/geolocation/v1/geolocate?key=$apiKey")

                val requestBody = """
                    {
                        "considerIp": "false",
                        "wifiAccessPoints": [
                            {"macAddress": "$macAddress"},
                            {"macAddress": "00:25:9c:cf:1c:ad"}
                        ]
                    }
                """.trimIndent()

                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                connection.outputStream.use {
                    it.write(requestBody.toByteArray())
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonResponse = JSONObject(response)
                val location = jsonResponse.getJSONObject("location")

                val locationResult = LocationResult(
                    module = "google",
                    bssid = macAddress,
                    latitude = location.getDouble("lat"),
                    longitude = location.getDouble("lng")
                )
                log("✅ Google search successful. Found location: ${locationResult.latitude}, ${locationResult.longitude}")
                addResult(locationResult)
            } catch (e: Exception) {
                log("❌ Google search error: ${e.message}")
                Log.e(TAG, "Google search error", e)
            }
        }

    private suspend fun searchCombain(macAddress: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting Combain search for MAC: $macAddress")

                val apiKey = _savedApiKeys.value?.combainApi ?: return@withContext
                val url = URL("https://apiv2.combain.com?key=$apiKey")

                val requestBody = """
                    {
                        "wifiAccessPoints": [
                            {"macAddress": "$macAddress"},
                            {"macAddress": "28:28:5d:d6:39:8a"}
                        ],
                        "indoor": 1
                    }
                """.trimIndent()

                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                connection.outputStream.use {
                    it.write(requestBody.toByteArray())
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonResponse = JSONObject(response)
                val location = jsonResponse.getJSONObject("location")

                val locationResult = LocationResult(
                    module = "combain",
                    bssid = macAddress,
                    latitude = location.getDouble("lat"),
                    longitude = location.getDouble("lng"),
                    vendor = jsonResponse.optJSONObject("indoor")?.optString("building")
                )
                log("✅ Combain search successful. Found location: ${locationResult.latitude}, ${locationResult.longitude}")
                addResult(locationResult)
            } catch (e: Exception) {
                log("❌ Combain search error: ${e.message}")
                Log.e(TAG, "Combain search error", e)
            }
        }

    private suspend fun searchWifiDB(query: String, isMac: Boolean) =
        withContext(Dispatchers.IO) {
            try {
                val searchType = if (isMac) "MAC" else "SSID"
                log("Starting WifiDB search by $searchType: $query")

                val params = if (isMac) {
                    "func=exp_search&mac=$query&ssid=&radio=&chan=&auth=&encry=&sectype=&json=0&labeled=0"
                } else {
                    "func=exp_search&mac=&ssid=$query&radio=&chan=&auth=&encry=&sectype=&json=0&labeled=0"
                }

                val url = URL("https://wifidb.net/wifidb/api/geojson.php?$params")

                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 30000
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonResponse = JSONObject(response)
                val features = jsonResponse.getJSONArray("features")

                if (features.length() > 0) {
                    val feature = features.getJSONObject(0)
                    val properties = feature.getJSONObject("properties")

                    val locationResult = LocationResult(
                        module = "wifidb",
                        bssid = if (isMac) query else properties.getString("mac"),
                        ssid = if (!isMac) query else properties.getString("ssid"),
                        latitude = properties.getDouble("lat"),
                        longitude = properties.getDouble("lon")
                    )
                    log("✅ WifiDB search successful. Found location: ${locationResult.latitude}, ${locationResult.longitude}")
                    addResult(locationResult)
                } else {
                    log("ℹ️ WifiDB search completed but no results found")
                }
            } catch (e: Exception) {
                log("❌ WifiDB search error: ${e.message}")
                Log.e(TAG, "WifiDB search error", e)
            }
        }

    private suspend fun searchOpenWifiMap(ssid: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting OpenWifiMap search for SSID: $ssid")

                val url = URL("https://api.openwifimap.net/view_nodes")

                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }

                val requestBody = """{"keys": ["$ssid"]}"""
                connection.outputStream.use {
                    it.write(requestBody.toByteArray())
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonResponse = JSONObject(response)
                val rows = jsonResponse.optJSONArray("rows")

                if (rows != null && rows.length() > 0) {
                    val value = rows.getJSONObject(0).getJSONObject("value")
                    val latlng = value.getJSONArray("latlng")

                    val locationResult = LocationResult(
                        module = "openwifimap",
                        ssid = ssid,
                        latitude = latlng.getDouble(0),
                        longitude = latlng.getDouble(1),
                        vendor = value.optString("hostname")
                    )
                    log("✅ OpenWifiMap search successful. Found location: ${locationResult.latitude}, ${locationResult.longitude}")
                    addResult(locationResult)
                } else {
                    log("ℹ️ OpenWifiMap search completed but no results found")
                }
            } catch (e: Exception) {
                log("❌ OpenWifiMap search error: ${e.message}")
                Log.e(TAG, "OpenWifiMap search error", e)
            }
        }

    private suspend fun searchFreifunkCarte(ssid: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting Freifunk-Karte search for SSID: $ssid")

                val url = URL("https://www.freifunk-karte.de/data.php")

                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonResponse = JSONObject(response)
                val routers = jsonResponse.getJSONArray("allTheRouters")

                for (i in 0 until routers.length()) {
                    val router = routers.getJSONObject(i)
                    if (router.optString("name", "").equals(ssid, ignoreCase = true)) {
                        val locationResult = LocationResult(
                            module = "freifunk-karte",
                            ssid = ssid,
                            latitude = router.getDouble("lat"),
                            longitude = router.getDouble("long"),
                            vendor = router.optString("community")
                        )
                        log("✅ Freifunk-Karte search successful. Found location: ${locationResult.latitude}, ${locationResult.longitude}")
                        addResult(locationResult)
                        return@withContext
                    }
                }

                log("ℹ️ Freifunk-Karte search completed but no results found")
            } catch (e: Exception) {
                log("❌ Freifunk-Karte search error: ${e.message}")
                Log.e(TAG, "Freifunk-Karte search error", e)
            }
        }

    fun isValidMacAddress(macAddress: String): Boolean {
        val regex = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        return regex.matches(macAddress)
    }

    companion object {
        private const val TAG = "MacLocationViewModel"
        private const val KEY_WIGLE_API = "wigle_api"
        private const val KEY_GOOGLE_API = "google_api"
        private const val KEY_COMBAIN_API = "combain_api"
    }
}