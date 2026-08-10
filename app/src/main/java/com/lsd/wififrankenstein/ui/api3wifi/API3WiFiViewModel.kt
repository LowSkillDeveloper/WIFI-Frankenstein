package com.lsd.wififrankenstein.ui.api3wifi

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.util.SslHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class API3WiFiViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsPrefs =
        application.getSharedPreferences("API3WiFiSettings", Context.MODE_PRIVATE)
    private val dbSetupViewModel by lazy { DbSetupViewModel(getApplication()) }

    private val _apiServers = MutableLiveData<List<DbItem>>()
    val apiServers: LiveData<List<DbItem>> = _apiServers

    private val _requestResult = MutableLiveData<String>()
    val requestResult: LiveData<String> = _requestResult

    private val _requestInfo = MutableLiveData<String>()
    val requestInfo: LiveData<String> = _requestInfo

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private var network: API3WiFiNetwork? = null
    private val wpasecHelper = WpaSecHelper()

    private val _wpasecResult = MutableLiveData<WpaSecHelper.WpaSecResult?>()
    val wpasecResult: LiveData<WpaSecHelper.WpaSecResult?> = _wpasecResult

    enum class RequestType {
        GET, POST_FORM, POST_JSON
    }

    fun loadApiServers() {
        viewModelScope.launch {
            dbSetupViewModel.loadDbList()
            _apiServers.value = dbSetupViewModel.dbList.value?.filter {
                it.dbType == DbType.WIFI_API
            } ?: emptyList()
        }
    }

    fun addApiServer(dbItem: DbItem) {
        dbSetupViewModel.addDb(dbItem)
        loadApiServers()
    }

    private fun createNetwork(serverUrl: String, readKey: String, writeKey: String?) {
        network = API3WiFiNetwork(
            context = getApplication(),
            serverUrl = serverUrl,
            connectTimeout = settingsPrefs.getInt("connectTimeout", 5000),
            readTimeout = settingsPrefs.getInt("readTimeout", 10000),
            ignoreSSL = settingsPrefs.getBoolean("ignoreSSLCertificate", false),
            includeAppIdentifier = settingsPrefs.getBoolean("includeAppIdentifier", true),
            apiReadKey = readKey,
            apiWriteKey = writeKey
        )
    }

    fun executeRequest(serverUrl: String, request: API3WiFiRequest, requestType: RequestType) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val server = _apiServers.value?.find { it.path == serverUrl }

                if (request is API3WiFiRequest.TrpcGetPoint || request is API3WiFiRequest.TrpcSearchNetworks) {
                    _requestInfo.value = formatTrpcRequestInfo(serverUrl, request)
                    val response = withContext(Dispatchers.IO) {
                        executeTrpcRequest(server, request)
                    }
                    _requestResult.value = response
                } else {
                    _requestInfo.value = formatRequestInfo(serverUrl, request, requestType)
                    val readKey = server?.apiReadKey ?: "000000000000"
                    val writeKey = server?.apiWriteKey
                    createNetwork(serverUrl, readKey, writeKey)
                    val response = withContext(Dispatchers.IO) {
                        network?.executeRequest(request, requestType)
                            ?: throw Exception("Network not initialized")
                    }
                    _requestResult.value = formatJsonResponse(response)
                }
            } catch (e: Exception) {
                _requestResult.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun checkWpaSec(bssid: String, ssid: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    wpasecHelper.checkBssidSsid(bssid, ssid)
                }
                _wpasecResult.value = result
            } catch (e: Exception) {
                _wpasecResult.value = WpaSecHelper.WpaSecResult(bssid, ssid, false, e.message)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun buildTrpcUrl(baseUrl: String, endpoint: String, params: JSONObject): String {
        val wrapped = JSONObject().apply { put("json", params) }
        val encoded = URLEncoder.encode(wrapped.toString(), "UTF-8")
        return "$baseUrl/trpc/$endpoint?input=$encoded"
    }

    private fun executeTrpcHttp(url: String, jwtToken: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        SslHelper.configure(connection)
        connection.requestMethod = "GET"
        if (jwtToken != null) connection.setRequestProperty("Authorization", "Bearer $jwtToken")
        connection.connectTimeout = 10000
        connection.readTimeout = 15000

        val code = connection.responseCode
        return if (code == HttpURLConnection.HTTP_OK) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err =
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            connection.disconnect()
            throw TrpcHttpException(code, err)
        }
    }

    private class TrpcHttpException(val code: Int, val errorBody: String) :
        Exception("Server error $code: $errorBody")

    private suspend fun performLogin(baseUrl: String, login: String, password: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val innerParams = JSONObject().apply {
                    put("usernameOrEmail", login)
                    put("password", password)
                }
                val wrapped = JSONObject().apply { put("json", innerParams) }
                val conn = URL("$baseUrl/trpc/login").openConnection() as HttpURLConnection
                SslHelper.configure(conn)
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.outputStream.write(wrapped.toString().toByteArray())

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val result = json.optJSONObject("result")
                    val data = result?.optJSONObject("data")
                    val innerJson = data?.optJSONObject("json")
                    innerJson?.optString("token", null)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun executeTrpcRequest(
        server: DbItem?,
        request: API3WiFiRequest,
        retryAttempt: Boolean = false
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = server?.path ?: return@withContext "Error: No server selected"
                var jwtToken = server?.jwtToken

                val (endpoint, params) = when (request) {
                    is API3WiFiRequest.TrpcGetPoint -> {
                        "getAccessPointDetails" to JSONObject().apply {
                            put("id", request.id)
                            jwtToken?.let { put("token", it) }
                        }
                    }

                    is API3WiFiRequest.TrpcSearchNetworks -> {
                        "searchNetworks" to JSONObject().apply {
                            if (request.type == "bssid") put("bssid", request.query)
                            else put("ssid", request.query)
                            put("limit", 100)
                            jwtToken?.let { put("token", it) }
                        }
                    }

                    else -> throw Exception("Unknown tRPC request type")
                }

                val url = buildTrpcUrl(baseUrl, endpoint, params)
                executeTrpcHttp(url, jwtToken)
            } catch (e: TrpcHttpException) {
                if (!retryAttempt && e.code == 401 && server?.login != null && server?.password != null) {
                    val newToken = performLogin(server.path!!, server.login!!, server.password!!)
                    if (newToken != null) {
                        val updatedServer = server.copy(jwtToken = newToken)
                        dbSetupViewModel.updateDbItem(updatedServer)

                        _apiServers.value = dbSetupViewModel.dbList.value?.filter {
                            it.dbType == DbType.WIFI_API
                        } ?: emptyList()
                        executeTrpcRequest(updatedServer, request, retryAttempt = true)
                    } else {
                        "Error: Re-login failed. Original: ${e.message}"
                    }
                } else {
                    "Error: ${e.message}"
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }

    private fun formatTrpcRequestInfo(serverUrl: String, request: API3WiFiRequest): String {
        val sb = StringBuilder()
        sb.appendLine("URL: $serverUrl/trpc/${request.methodName}")
        sb.appendLine("Protocol: tRPC (3WiFi App)")
        sb.appendLine()
        sb.appendLine("Parameters:")
        when (request) {
            is API3WiFiRequest.TrpcGetPoint -> sb.appendLine("id: ${request.id}")
            is API3WiFiRequest.TrpcSearchNetworks -> {
                sb.appendLine("${request.type}: ${request.query}")
                sb.appendLine("limit: 100")
            }

            else -> {}
        }
        return sb.toString()
    }

    fun executeSimpleRequestWithRetry(serverUrl: String, request: API3WiFiRequest) {
        viewModelScope.launch {
            _isLoading.value = true
            _requestInfo.value = formatRequestInfo(serverUrl, request, RequestType.POST_JSON)
            try {
                val server = _apiServers.value?.find { it.path == serverUrl }
                val readKey = server?.apiReadKey ?: "000000000000"
                val writeKey = server?.apiWriteKey

                createNetwork(serverUrl, readKey, writeKey)

                var finalResponse = ""

                try {
                    val postResponse = withContext(Dispatchers.IO) {
                        network?.executeRequest(request, RequestType.POST_JSON)
                            ?: throw Exception("Network not initialized")
                    }

                    if (isSuccessfulResponse(postResponse)) {
                        finalResponse = formatJsonResponse(postResponse)
                    } else {
                        throw Exception("POST returned result: false")
                    }
                } catch (e: Exception) {
                    finalResponse =
                        "${getApplication<Application>().getString(R.string.post_request_failed)}\n" +
                                "${getApplication<Application>().getString(R.string.separator_line)}\n" +
                                "Error: ${e.message}\n\n"

                    finalResponse += "${getApplication<Application>().getString(R.string.retry_with_get)}\n" +
                            "${getApplication<Application>().getString(R.string.separator_line)}\n\n"

                    try {
                        val getResponse = withContext(Dispatchers.IO) {
                            network?.executeRequest(request, RequestType.GET)
                                ?: throw Exception("Network not initialized")
                        }

                        finalResponse += "${getApplication<Application>().getString(R.string.get_request_response)}\n" +
                                "${getApplication<Application>().getString(R.string.separator_line)}\n" +
                                formatJsonResponse(getResponse)
                    } catch (e2: Exception) {
                        finalResponse += "${getApplication<Application>().getString(R.string.get_request_response)}\n" +
                                "${getApplication<Application>().getString(R.string.separator_line)}\n" +
                                "Error: ${e2.message}"
                    }
                }

                _requestResult.value = finalResponse
            } catch (e: Exception) {
                _requestResult.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun formatRequestInfo(
        serverUrl: String,
        request: API3WiFiRequest,
        requestType: RequestType
    ): String {
        val sb = StringBuilder()
        sb.appendLine("URL: $serverUrl/api/${request.methodName}")
        sb.appendLine("Method: ${requestType.name}")
        sb.appendLine(
            "Request Type: ${
                when (requestType) {
                    RequestType.GET -> "GET"
                    RequestType.POST_FORM -> "POST (Form Data)"
                    RequestType.POST_JSON -> "POST (JSON)"
                }
            }"
        )
        sb.appendLine()

        when (requestType) {
            RequestType.GET -> {
                sb.appendLine("Query Parameters:")
                addRequestParams(sb, request)
            }

            RequestType.POST_FORM -> {
                sb.appendLine("Form Data:")
                addRequestParams(sb, request)
            }

            RequestType.POST_JSON -> {
                sb.appendLine("JSON Body:")
                sb.append(createJsonBodyString(request))
            }
        }

        return sb.toString()
    }

    private fun addRequestParams(sb: StringBuilder, request: API3WiFiRequest) {
        when (request) {
            is API3WiFiRequest.ApiQuery -> {
                sb.appendLine("key: ${request.key}")
                request.bssidList?.let { list ->
                    sb.appendLine("bssid: ${if (list.size == 1) list.first() else JSONArray(list).toString()}")
                }
                request.essidList?.let { list ->
                    sb.appendLine("essid: ${if (list.size == 1) list.first() else JSONArray(list).toString()}")
                }
                sb.appendLine("sens: ${request.sens}")
            }

            is API3WiFiRequest.ApiWps -> {
                sb.appendLine("key: ${request.key}")
                sb.appendLine(
                    "bssid: ${
                        if (request.bssidList.size == 1) request.bssidList.first() else JSONArray(
                            request.bssidList
                        ).toString()
                    }"
                )
            }

            is API3WiFiRequest.ApiDev -> {
                sb.appendLine("key: ${request.key}")
                sb.appendLine(
                    "bssid: ${
                        if (request.bssidList.size == 1) request.bssidList.first() else JSONArray(
                            request.bssidList
                        ).toString()
                    }"
                )
                sb.appendLine("nocli: ${request.nocli}")
            }

            is API3WiFiRequest.ApiRanges -> {
                sb.appendLine("key: ${request.key}")
                sb.appendLine("lat: ${request.lat}")
                sb.appendLine("lon: ${request.lon}")
                sb.appendLine("rad: ${request.rad}")
            }

            is API3WiFiRequest.ApiKeys -> {
                sb.appendLine("login: ${request.login}")
                sb.appendLine("password: ${request.password}")
                sb.appendLine("genread: ${request.genRead}")
                sb.appendLine("genwrite: ${request.genWrite}")
            }

            is API3WiFiRequest.TrpcGetPoint -> {
                sb.appendLine("id: ${request.id}")
            }

            is API3WiFiRequest.TrpcSearchNetworks -> {
                sb.appendLine("${request.type}: ${request.query}")
            }
        }
    }

    private fun createJsonBodyString(request: API3WiFiRequest): String {
        val jsonObject = when (request) {
            is API3WiFiRequest.ApiKeys -> JSONObject().apply {
                put("login", request.login)
                put("password", request.password)
                put("genread", request.genRead)
                put("genwrite", request.genWrite)
            }

            is API3WiFiRequest.ApiQuery -> JSONObject().apply {
                put("key", request.key)
                request.bssidList?.let { list ->
                    put("bssid", if (list.size == 1) list.first() else JSONArray(list))
                }
                request.essidList?.let { list ->
                    put("essid", if (list.size == 1) list.first() else JSONArray(list))
                }
                put("sens", request.sens)
            }

            is API3WiFiRequest.ApiWps -> JSONObject().apply {
                put("key", request.key)
                put(
                    "bssid",
                    if (request.bssidList.size == 1) request.bssidList.first() else JSONArray(
                        request.bssidList
                    )
                )
            }

            is API3WiFiRequest.ApiDev -> JSONObject().apply {
                put("key", request.key)
                put(
                    "bssid",
                    if (request.bssidList.size == 1) request.bssidList.first() else JSONArray(
                        request.bssidList
                    )
                )
                put("nocli", request.nocli)
            }

            is API3WiFiRequest.ApiRanges -> JSONObject().apply {
                put("key", request.key)
                put("lat", request.lat)
                put("lon", request.lon)
                put("rad", request.rad)
            }

            is API3WiFiRequest.TrpcGetPoint -> JSONObject().apply {
                put("id", request.id)
            }

            is API3WiFiRequest.TrpcSearchNetworks -> JSONObject().apply {
                put(request.type, request.query)
                put("limit", 100)
            }
        }
        return jsonObject.toString(4)
    }

    private fun isSuccessfulResponse(jsonString: String): Boolean {
        return try {
            val json = JSONObject(jsonString)
            json.optBoolean("result", false)
        } catch (_: Exception) {
            false
        }
    }

    private fun formatJsonResponse(jsonString: String): String {
        return try {
            val json = JSONObject(jsonString)
            json.toString(4)
        } catch (_: Exception) {
            try {
                val jsonArray = JSONArray(jsonString)
                jsonArray.toString(4)
            } catch (_: Exception) {
                jsonString
            }
        }
    }
}
