package com.lsd.wififrankenstein.ui.maclocation

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.models.AppleWLoc
import com.lsd.wififrankenstein.models.WifiDevice
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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
        val combainApi: String,
        val yandexLocatorApi: String,
        val mylnikovApi: String
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
        val yandexLocatorApi = prefs.getString(KEY_YANDEX_LOCATOR_API, "") ?: ""
        val mylnikovApi = prefs.getString(KEY_MYLNIKOV_API, "") ?: ""
        _savedApiKeys.value = ApiKeys(wigleApi, googleApi, combainApi, yandexLocatorApi, mylnikovApi)
    }

    fun saveApiKeys(wigleApi: String, googleApi: String, combainApi: String, yandexLocatorApi: String, mylnikovApi: String) {
        log("Saving API keys...")
        prefs.edit().apply {
            putString(KEY_WIGLE_API, wigleApi)
            putString(KEY_GOOGLE_API, googleApi)
            putString(KEY_COMBAIN_API, combainApi)
            putString(KEY_YANDEX_LOCATOR_API, yandexLocatorApi)
            putString(KEY_MYLNIKOV_API, mylnikovApi)
            apply()
        }
        _savedApiKeys.value = ApiKeys(wigleApi, googleApi, combainApi, yandexLocatorApi, mylnikovApi)
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
            val totalTasks = 12

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

                launch {
                    searchAlterGeo(mac)
                    checkCompletion()
                }

                if (_savedApiKeys.value?.yandexLocatorApi?.isNotBlank() == true) {
                    launch {
                        searchYandexLocator(mac)
                        checkCompletion()
                    }
                } else {
                    checkCompletion()
                }

                if (_savedApiKeys.value?.mylnikovApi?.isNotBlank() == true) {
                    launch {
                        searchMylnikovPaid(mac)
                        checkCompletion()
                    }
                } else {
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

                launch {
                    searchGoogleNoKey(mac)
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

                launch {
                    searchYandex(mac)
                    checkCompletion()
                }
                launch {
                    searchMicrosoft(mac)
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

    private suspend fun searchGoogleNoKey(macAddress: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting Google (No Key) search for MAC: $macAddress")

                val data1 = byteArrayOf(
                    0x0A.toByte(), 0x66, 0x0A, 0x04, 0x32, 0x30, 0x32, 0x31, 0x12, 0x57, 0x61, 0x6E, 0x64, 0x72, 0x6F, 0x69,
                    0x64, 0x2F, 0x4C, 0x45, 0x41, 0x47, 0x4F, 0x4F, 0x2F, 0x66, 0x75, 0x6C, 0x6C, 0x5F, 0x77, 0x66,
                    0x35, 0x36, 0x32, 0x67, 0x5F, 0x6C, 0x65, 0x61, 0x67, 0x6F, 0x6F, 0x2F, 0x77, 0x66, 0x35, 0x36,
                    0x32, 0x67, 0x5F, 0x6C, 0x65, 0x61, 0x67, 0x6F, 0x6F, 0x3A, 0x36, 0x2E, 0x30, 0x2F, 0x4D, 0x52,
                    0x41, 0x35, 0x38, 0x4B, 0x2F, 0x31, 0x35, 0x31, 0x31, 0x31, 0x36, 0x31, 0x37, 0x37, 0x30, 0x3A,
                    0x75, 0x73, 0x65, 0x72, 0x2F, 0x72, 0x65, 0x6C, 0x65, 0x61, 0x73, 0x65, 0x2D, 0x6B, 0x65, 0x79,
                    0x73, 0x2A, 0x05, 0x65, 0x6E, 0x5F, 0x55, 0x53, 0x22, 0x22, 0x12, 0x1E, 0x08, 0xA3.toByte(), 0xF7.toByte(), 0x09,
                    0x12, 0x0A, 0x0A, 0x00, 0x40, 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0x6E,
                    0x12, 0x0A, 0x0A, 0x00, 0x40, 0xE6.toByte(), 0xAA.toByte(), 0x91.toByte(), 0x9A.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0x04,
                    0x18, 0x02, 0x50, 0x00
                )

                val data2_1 = byteArrayOf(
                    0x00, 0x02, 0x00, 0x00, 0x1F, 0x6C, 0x6F, 0x63, 0x61, 0x74, 0x69, 0x6F, 0x6E, 0x2C, 0x32, 0x30,
                    0x32, 0x31, 0x2C, 0x61, 0x6E, 0x64, 0x72, 0x6F, 0x69, 0x64, 0x2C, 0x67, 0x6D, 0x73, 0x2C, 0x65,
                    0x6E, 0x5F, 0x55, 0x53, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x67, 0x00,
                    0x00, 0x00, 0xBB.toByte(), 0x00, 0x01, 0x01, 0x00, 0x01, 0x00, 0x08, 0x67, 0x3A, 0x6C, 0x6F, 0x63, 0x2F,
                    0x71, 0x6C, 0x00, 0x00, 0x00, 0x04, 0x50, 0x4F, 0x53, 0x54, 0x6D, 0x72, 0x00, 0x00, 0x00, 0x04,
                    0x52, 0x4F, 0x4F, 0x54, 0x00, 0x00, 0x00, 0x00
                )

                val macLong = macAddress.replace(":", "").replace("-", "").toLong(16)

                insertMac(data1, 0x75, macLong)

                val compressedData1 = gzipCompress(data1)

                val data2 = constructData2(data2_1, compressedData1)

                val url = URL("https://www.google.com/loc/m/api")
                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/binary")
                    setRequestProperty("User-Agent", "GoogleMobile/1.0 (M5 MRA58K); gzip")
                    setRequestProperty("Accept-Encoding", "gzip")
                }

                connection.outputStream.use { it.write(data2) }

                val response = connection.inputStream.readBytes()

                val location = findLocationInResponse(response)
                if (location.latitude != 0.0 && location.longitude != 0.0) {
                    val locationResult = LocationResult(
                        module = "google_nokey",
                        bssid = macAddress,
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                    log("✅ Google (No Key) search successful. Found location: ${location.latitude}, ${location.longitude}")
                    addResult(locationResult)
                } else {
                    log("ℹ️ Google (No Key) search completed but no results found")
                }

            } catch (e: Exception) {
                log("❌ Google (No Key) search error: ${e.message}")
                Log.e(TAG, "Google (No Key) search error", e)
            }
        }

    private fun insertMac(buffer: ByteArray, position: Int, macValue: Long) {
        var input = macValue
        var i = 0
        while (i < 6) {
            buffer[i + position] = ((input and 0x7F) or 0x80).toByte()
            input = input ushr 7
            i++
        }
        buffer[i + position] = input.toByte()
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        val gzipOutputStream = GZIPOutputStream(byteArrayOutputStream)
        gzipOutputStream.write(data)
        gzipOutputStream.close()
        return byteArrayOutputStream.toByteArray()
    }

    private fun gzipDecompress(compressedData: ByteArray): ByteArray {
        val byteArrayInputStream = ByteArrayInputStream(compressedData)
        val gzipInputStream = GZIPInputStream(byteArrayInputStream)
        return gzipInputStream.readBytes()
    }

    private fun constructData2(data2_1: ByteArray, compressedData: ByteArray): ByteArray {
        val buffer = ByteArray(1024)
        System.arraycopy(data2_1, 0, buffer, 0, data2_1.size)

        val len = compressedData.size
        buffer[88] = (len and 0xFF).toByte()
        buffer[89] = ((len shr 8) and 0xFF).toByte()
        buffer[90] = 0x01
        buffer[91] = 0x67

        System.arraycopy(compressedData, 0, buffer, 92, len)
        buffer[92 + len] = 0x00
        buffer[93 + len] = 0x00

        val outLen = data2_1.size + 6 + len
        return buffer.copyOf(outLen)
    }

    private data class Location(val latitude: Double = 0.0, val longitude: Double = 0.0)

    private fun findLocationInResponse(response: ByteArray): Location {
        var posGzip = -1
        for (i in 0 until response.size - 2) {
            if (response[i] == 0x1F.toByte() && response[i + 1] == 0x8B.toByte() && response[i + 2] == 0x08.toByte()) {
                posGzip = i
                break
            }
        }

        if (posGzip != -1) {
            try {
                val compressedData = response.copyOfRange(posGzip, response.size)
                val decompressedData = gzipDecompress(compressedData)

                for (i in 0 until decompressedData.size - 12) {
                    if (decompressedData[i] == 0x0A.toByte() &&
                        decompressedData[i + 1] == 0x0A.toByte() &&
                        decompressedData[i + 12] == 24.toByte()) {

                        val lat = (decompressedData[i + 3].toInt() and 0xFF) or
                                ((decompressedData[i + 4].toInt() and 0xFF) shl 8) or
                                ((decompressedData[i + 5].toInt() and 0xFF) shl 16) or
                                ((decompressedData[i + 6].toInt() and 0xFF) shl 24)

                        val lon = (decompressedData[i + 8].toInt() and 0xFF) or
                                ((decompressedData[i + 9].toInt() and 0xFF) shl 8) or
                                ((decompressedData[i + 10].toInt() and 0xFF) shl 16) or
                                ((decompressedData[i + 11].toInt() and 0xFF) shl 24)

                        return Location(lat / 10000000.0, lon / 10000000.0)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error decompressing response", e)
            }
        }

        return Location()
    }

    private suspend fun searchMylnikovPaid(macAddress: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting Mylnikov Paid search for MAC: $macAddress")

                val url = URL("https://api.mylnikov.org/wifi/main.py/get?bssid=$macAddress")

                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonResponse = JSONObject(response)

                if (jsonResponse.getInt("result") == 200) {
                    val data = jsonResponse.getJSONObject("data")
                    val lat = data.getDouble("lat")
                    val lon = data.getDouble("lon")

                    if (isValidCoordinates(lat, lon)) {
                        val locationResult = LocationResult(
                            module = "mylnikov_paid",
                            bssid = macAddress,
                            latitude = lat,
                            longitude = lon
                        )
                        log("✅ Mylnikov Paid search successful. Found location: $lat, $lon")
                        addResult(locationResult)
                    } else {
                        log("ℹ️ Mylnikov Paid search completed but coordinates are invalid")
                    }
                } else {
                    log("ℹ️ Mylnikov Paid search completed but no results found")
                }
            } catch (e: Exception) {
                log("❌ Mylnikov Paid search error: ${e.message}")
                Log.e(TAG, "Mylnikov Paid search error", e)
            }
        }

    private suspend fun searchAlterGeo(macAddress: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting AlterGeo search for MAC: $macAddress")

                val cleanMac = macAddress.lowercase().replace(":", "-")
                val url = URL("http://api.platform.altergeo.ru/loc/json?browser=firefox&sensor=false&wifi=mac:$cleanMac%7Css:0")

                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val jsonResponse = JSONObject(response)

                if (jsonResponse.getString("status") == "OK") {
                    val accuracy = jsonResponse.optDouble("accuracy", Double.MAX_VALUE)
                    if (accuracy < 1000) {
                        val location = jsonResponse.getJSONObject("location")
                        val lat = location.getDouble("lat")
                        val lon = location.getDouble("lng")

                        if (isValidCoordinates(lat, lon)) {
                            val locationResult = LocationResult(
                                module = "altergeo",
                                bssid = macAddress,
                                latitude = lat,
                                longitude = lon
                            )
                            log("✅ AlterGeo search successful. Found location: $lat, $lon")
                            addResult(locationResult)
                        } else {
                            log("ℹ️ AlterGeo search completed but coordinates are invalid")
                        }
                    } else {
                        log("ℹ️ AlterGeo search completed but accuracy too low: $accuracy")
                    }
                } else {
                    log("ℹ️ AlterGeo search completed but no results found")
                }
            } catch (e: Exception) {
                log("❌ AlterGeo search error: ${e.message}")
                Log.e(TAG, "AlterGeo search error", e)
            }
        }

    private suspend fun searchYandexLocator(macAddress: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting Yandex Locator search for MAC: $macAddress")

                val apiKey = _savedApiKeys.value?.yandexLocatorApi ?: return@withContext

                val xmlRequest = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ya_lbs_request xmlns="http://api.lbs.yandex.net/geolocation">
                    <common>
                        <version>1.0</version>
                        <api_key>$apiKey</api_key>
                    </common>
                    <wifi_networks>
                        <network>
                            <mac>$macAddress</mac>
                        </network>
                    </wifi_networks>
                </ya_lbs_request>
            """.trimIndent()

                val url = URL("http://api.lbs.yandex.net/geolocation")

                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "text/xml")
                }

                val postData = "xml=" + java.net.URLEncoder.encode(xmlRequest, "UTF-8")
                connection.outputStream.use {
                    it.write(postData.toByteArray())
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val typeMatch = Regex("<type>([^<]+)</type>").find(response)
                val latMatch = Regex("<latitude>([^<]+)</latitude>").find(response)
                val lonMatch = Regex("<longitude>([^<]+)</longitude>").find(response)

                if (typeMatch != null && typeMatch.groupValues[1] == "wifi" &&
                    latMatch != null && lonMatch != null) {

                    val lat = latMatch.groupValues[1].toDoubleOrNull()
                    val lon = lonMatch.groupValues[1].toDoubleOrNull()

                    if (lat != null && lon != null && isValidCoordinates(lat, lon)) {
                        val locationResult = LocationResult(
                            module = "yandex_locator",
                            bssid = macAddress,
                            latitude = lat,
                            longitude = lon
                        )
                        log("✅ Yandex Locator search successful. Found location: $lat, $lon")
                        addResult(locationResult)
                    } else {
                        log("ℹ️ Yandex Locator search completed but coordinates are invalid")
                    }
                } else {
                    log("ℹ️ Yandex Locator search completed but no results found")
                }
            } catch (e: Exception) {
                log("❌ Yandex Locator search error: ${e.message}")
                Log.e(TAG, "Yandex Locator search error", e)
            }
        }

    private suspend fun searchYandex(macAddress: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting Yandex search for MAC: $macAddress")

                val cleanMac = macAddress.replace(":", "").replace("-", "")
                val url = URL("http://mobile.maps.yandex.net/cellid_location/?clid=1866854&lac=-1&cellid=-1&operatorid=null&countrycode=null&signalstrength=-1&wifinetworks=$cleanMac:0&app")

                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "GET"
                }

                val responseCode = connection.responseCode
                val response = if (responseCode == 404) {
                    "<error code=\"6\">Not found</error>"
                } else {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }

                val latitude = getStringBetween(response, " latitude=\"", "\"")
                val longitude = getStringBetween(response, " longitude=\"", "\"")

                if (latitude.isNotEmpty() && longitude.isNotEmpty()) {
                    val lat = latitude.toDoubleOrNull()
                    val lon = longitude.toDoubleOrNull()

                    if (lat != null && lon != null && isValidCoordinates(lat, lon)) {
                        val locationResult = LocationResult(
                            module = "yandex",
                            bssid = macAddress,
                            latitude = lat,
                            longitude = lon
                        )
                        log("✅ Yandex search successful. Found location: $lat, $lon")
                        addResult(locationResult)
                    } else {
                        log("ℹ️ Yandex search completed but coordinates are invalid")
                    }
                } else {
                    log("ℹ️ Yandex search completed but no results found")
                }
            } catch (e: Exception) {
                if (e.message?.contains("Unable to resolve host") == true ||
                    e.message?.contains("No address associated with hostname") == true) {
                    log("❌ Yandex search (without a key) error: Exploit unavailable, use Yandex with API key")
                } else {
                    log("❌ Yandex search error: ${e.message}")
                }
                Log.e(TAG, "Yandex search error", e)
            }
        }

    private suspend fun searchMicrosoft(macAddress: String) =
        withContext(Dispatchers.IO) {
            try {
                log("Starting Microsoft search for MAC: $macAddress")

                val cleanMac = macAddress.lowercase().replace("-", ":")

                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val timestamp = sdf.format(Date())

                val xmlRequest = """
                <GetLocationUsingFingerprint xmlns="http://inference.location.live.com">
                    <RequestHeader>
                        <Timestamp>$timestamp</Timestamp>
                        <ApplicationId>e1e71f6b-2149-45f3-a298-a20682ab5017</ApplicationId>
                        <TrackingId>21BF9AD6-CFD3-46B3-B041-EE90BD34FDBC</TrackingId>
                        <DeviceProfile ClientGuid="0fc571be-4624-4ce0-b04e-911bdeb1a222" Platform="Android" DeviceType="Mobile" OSVersion="Android" LFVersion="1.0" ExtendedDeviceInfo="" />
                        <Authorization />
                    </RequestHeader>
                    <BeaconFingerprint>
                        <Detections>
                            <Wifi7 BssId="$cleanMac" rssi="-1" />
                        </Detections>
                    </BeaconFingerprint>
                </GetLocationUsingFingerprint>
            """.trimIndent()

                val url = URL("https://inference.location.live.net/inferenceservice/v21/Pox/GetLocationUsingFingerprint")

                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "text/xml")
                    setRequestProperty("User-Agent", "Nim httpclient/1.4.2")
                }

                connection.outputStream.use {
                    it.write(xmlRequest.toByteArray())
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }

                val regex = Regex("""<ResolvedPosition\s+Latitude="([^"]+)"\s+Longitude="([^"]+)"""")
                val match = regex.find(response)

                if (match != null) {
                    val lat = match.groupValues[1].toDouble()
                    val lon = match.groupValues[2].toDouble()

                    if (isValidCoordinates(lat, lon)) {
                        val locationResult = LocationResult(
                            module = "microsoft",
                            bssid = macAddress,
                            latitude = lat,
                            longitude = lon
                        )
                        log("✅ Microsoft search successful. Found location: $lat, $lon")
                        addResult(locationResult)
                    } else {
                        log("ℹ️ Microsoft search completed but results are invalid")
                    }
                } else {
                    log("ℹ️ Microsoft search completed but no results found")
                }
            } catch (e: Exception) {
                log("❌ Microsoft search error: ${e.message}")
                Log.e(TAG, "Microsoft search error", e)
            }
        }

    private fun getStringBetween(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        if (startIndex == -1) return ""

        val startPos = startIndex + start.length
        val endIndex = source.indexOf(end, startPos)
        if (endIndex == -1) return ""

        return source.substring(startPos, endIndex)
    }

    private fun isValidCoordinates(lat: Double, lon: Double): Boolean {
        if (lat == 0.0 && lon == 0.0) return false
        if (lat == 12.3456 && lon == -7.891) return false
        if (lat >= 56.864 && lat <= 56.865 && lon >= 60.610 && lon <= 60.612) return false
        return true
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
        private const val KEY_YANDEX_LOCATOR_API = "yandex_locator_api"
        private const val KEY_MYLNIKOV_API = "mylnikov_api"
    }
}