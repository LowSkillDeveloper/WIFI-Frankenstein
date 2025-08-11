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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class API3WiFiViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsPrefs = application.getSharedPreferences("API3WiFiSettings", Context.MODE_PRIVATE)

    private val _apiServers = MutableLiveData<List<DbItem>>()
    val apiServers: LiveData<List<DbItem>> = _apiServers

    private val _requestResult = MutableLiveData<String>()
    val requestResult: LiveData<String> = _requestResult

    private val _requestInfo = MutableLiveData<String>()
    val requestInfo: LiveData<String> = _requestInfo

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private var network: API3WiFiNetwork? = null

    enum class RequestType {
        GET, POST_FORM, POST_JSON
    }

    fun loadApiServers() {
        viewModelScope.launch {
            val dbSetupViewModel = DbSetupViewModel(getApplication())
            dbSetupViewModel.loadDbList()
            _apiServers.value = dbSetupViewModel.dbList.value?.filter {
                it.dbType == DbType.WIFI_API
            } ?: emptyList()
        }
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
            _requestInfo.value = formatRequestInfo(serverUrl, request, requestType)
            try {
                val server = _apiServers.value?.find { it.path == serverUrl }
                val readKey = server?.apiReadKey ?: "000000000000"
                val writeKey = server?.apiWriteKey

                createNetwork(serverUrl, readKey, writeKey)

                val response = withContext(Dispatchers.IO) {
                    network?.executeRequest(request, requestType)
                        ?: throw Exception("Network not initialized")
                }

                _requestResult.value = formatJsonResponse(response)
            } catch (e: Exception) {
                _requestResult.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
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
                var postSuccessful = false

                try {
                    val postResponse = withContext(Dispatchers.IO) {
                        network?.executeRequest(request, RequestType.POST_JSON)
                            ?: throw Exception("Network not initialized")
                    }

                    if (isSuccessfulResponse(postResponse)) {
                        postSuccessful = true
                        finalResponse = formatJsonResponse(postResponse)
                    } else {
                        finalResponse = "${getApplication<Application>().getString(R.string.post_request_failed)}\n" +
                                "${getApplication<Application>().getString(R.string.separator_line)}\n" +
                                formatJsonResponse(postResponse) + "\n\n"
                    }
                } catch (e: Exception) {
                    finalResponse = "${getApplication<Application>().getString(R.string.post_request_failed)}\n" +
                            "${getApplication<Application>().getString(R.string.separator_line)}\n" +
                            "Error: ${e.message}\n\n"
                }

                if (!postSuccessful) {
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
                    } catch (e: Exception) {
                        finalResponse += "${getApplication<Application>().getString(R.string.get_request_response)}\n" +
                                "${getApplication<Application>().getString(R.string.separator_line)}\n" +
                                "Error: ${e.message}"
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

    private fun formatRequestInfo(serverUrl: String, request: API3WiFiRequest, requestType: RequestType): String {
        val sb = StringBuilder()
        sb.appendLine("URL: $serverUrl/api/${request.methodName}")
        sb.appendLine("Method: ${requestType.name}")
        sb.appendLine("Request Type: ${when(requestType) {
            RequestType.GET -> "GET"
            RequestType.POST_FORM -> "POST (Form Data)"
            RequestType.POST_JSON -> "POST (JSON)"
        }}")
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
                sb.appendLine("bssid: ${if (request.bssidList.size == 1) request.bssidList.first() else JSONArray(request.bssidList).toString()}")
            }
            is API3WiFiRequest.ApiDev -> {
                sb.appendLine("key: ${request.key}")
                sb.appendLine("bssid: ${if (request.bssidList.size == 1) request.bssidList.first() else JSONArray(request.bssidList).toString()}")
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
                put("bssid", if (request.bssidList.size == 1) request.bssidList.first() else JSONArray(request.bssidList))
            }
            is API3WiFiRequest.ApiDev -> JSONObject().apply {
                put("key", request.key)
                put("bssid", if (request.bssidList.size == 1) request.bssidList.first() else JSONArray(request.bssidList))
                put("nocli", request.nocli)
            }
            is API3WiFiRequest.ApiRanges -> JSONObject().apply {
                put("key", request.key)
                put("lat", request.lat)
                put("lon", request.lon)
                put("rad", request.rad)
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