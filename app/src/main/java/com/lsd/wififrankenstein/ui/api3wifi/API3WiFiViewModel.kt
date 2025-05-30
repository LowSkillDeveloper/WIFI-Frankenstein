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

    private fun createNetwork(serverUrl: String) {
        network = API3WiFiNetwork(
            context = getApplication(),
            serverUrl = serverUrl,
            connectTimeout = settingsPrefs.getInt("connectTimeout", 5000),
            readTimeout = settingsPrefs.getInt("readTimeout", 10000),
            ignoreSSL = settingsPrefs.getBoolean("ignoreSSLCertificate", false),
            includeAppIdentifier = settingsPrefs.getBoolean("includeAppIdentifier", true)
        )
    }

    fun executeRequest(serverUrl: String, request: API3WiFiRequest, requestType: RequestType) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                createNetwork(serverUrl)

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
            try {
                createNetwork(serverUrl)

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