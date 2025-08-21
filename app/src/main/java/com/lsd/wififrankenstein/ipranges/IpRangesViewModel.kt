package com.lsd.wififrankenstein.ui.ipranges

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.api3wifi.API3WiFiNetwork
import com.lsd.wififrankenstein.ui.api3wifi.API3WiFiRequest
import com.lsd.wififrankenstein.ui.api3wifi.API3WiFiViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.launch
import org.json.JSONObject

class IpRangesViewModel(application: Application) : AndroidViewModel(application) {

    private val _sources = MutableLiveData<List<IpRangeSource>>()
    val sources: LiveData<List<IpRangeSource>> = _sources

    private val _ipRanges = MutableLiveData<List<IpRangeResult>>()
    val ipRanges: LiveData<List<IpRangeResult>> = _ipRanges

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val dbSetupViewModel = DbSetupViewModel(application)

    fun loadSources() {
        viewModelScope.launch {
            try {
                dbSetupViewModel.loadDbList()
                val dbList = dbSetupViewModel.dbList.value ?: emptyList()

                val sources = mutableListOf<IpRangeSource>()

                dbList.forEach { dbItem ->
                    when (dbItem.dbType) {
                        DbType.SQLITE_FILE_3WIFI, DbType.SQLITE_FILE_CUSTOM -> {
                            sources.add(
                                IpRangeSource(
                                    id = dbItem.id,
                                    name = formatDatabaseName(dbItem.path),
                                    type = IpRangeSourceType.LOCAL_DATABASE,
                                    isSelected = true,
                                    dbItem = dbItem
                                )
                            )
                        }
                        DbType.WIFI_API -> {
                            sources.add(
                                IpRangeSource(
                                    id = dbItem.id,
                                    name = formatApiName(dbItem.path, dbItem.userNick),
                                    type = IpRangeSourceType.API,
                                    isSelected = true,
                                    dbItem = dbItem
                                )
                            )
                        }
                        else -> {
                            // Пропускаем неподдерживаемые типы
                        }
                    }
                }

                _sources.value = sources
                Log.d("IpRangesViewModel", "Loaded ${sources.size} sources")
            } catch (e: Exception) {
                Log.e("IpRangesViewModel", "Error loading sources", e)
                _error.value = e.message ?: getApplication<Application>().getString(R.string.unknown_error)
            }
        }
    }

    private fun formatDatabaseName(path: String): String {
        return try {
            when {
                path.startsWith("content://") -> {
                    val uri = android.net.Uri.parse(path)
                    uri.lastPathSegment?.let { lastSegment ->
                        val decodedSegment = android.net.Uri.decode(lastSegment)
                        decodedSegment.substringAfterLast('/')
                    } ?: path
                }
                path.startsWith("file://") -> {
                    val uri = android.net.Uri.parse(path)
                    uri.lastPathSegment ?: path
                }
                path == "local_db" -> getApplication<Application>().getString(R.string.local_database)
                else -> {
                    path.substringAfterLast('/')
                }
            }.substringAfterLast("%2F").substringBeforeLast(".").ifEmpty {
                path.substringAfterLast('/').substringBeforeLast('.')
            }
        } catch (e: Exception) {
            Log.e("IpRangesViewModel", "Error formatting database name: $path", e)
            path.substringAfterLast('/').substringBeforeLast('.')
        }
    }

    private fun formatApiName(path: String, userNick: String?): String {
        return try {
            val hostName = when {
                path.startsWith("http://") || path.startsWith("https://") -> {
                    val uri = android.net.Uri.parse(path)
                    uri.host ?: path
                }
                else -> path
            }

            val baseApiName = if (!userNick.isNullOrEmpty()) {
                "$hostName ($userNick)"
            } else {
                hostName
            }

            "$baseApiName ${getApplication<Application>().getString(R.string.online_api_suffix)}"
        } catch (e: Exception) {
            Log.e("IpRangesViewModel", "Error formatting API name: $path", e)
            "$path ${getApplication<Application>().getString(R.string.online_api_suffix)}"
        }
    }

    fun updateSourceSelection(sourceId: String, isSelected: Boolean) {
        val currentSources = _sources.value?.toMutableList() ?: return
        val index = currentSources.indexOfFirst { it.id == sourceId }
        if (index != -1) {
            currentSources[index] = currentSources[index].copy(isSelected = isSelected)
            _sources.value = currentSources
        }
    }

    fun searchIpRanges(latitude: Double, longitude: Double, radius: Double, selectedSourceIds: List<String>) {
        viewModelScope.launch {
            Log.d("IpRangesViewModel", "Starting search: lat=$latitude, lon=$longitude, radius=$radius")
            _isLoading.value = true
            _ipRanges.value = emptyList()

            try {
                val selectedSources = _sources.value?.filter {
                    selectedSourceIds.contains(it.id)
                } ?: emptyList()

                Log.d("IpRangesViewModel", "Selected sources: ${selectedSources.size}")

                val allResults = mutableListOf<IpRangeResult>()

                selectedSources.forEach { source ->
                    val results = when (source.type) {
                        IpRangeSourceType.LOCAL_DATABASE -> {
                            searchLocalDatabase(source, latitude, longitude, radius)
                        }
                        IpRangeSourceType.API -> {
                            searchApi(source, latitude, longitude, radius)
                        }
                    }
                    allResults.addAll(results)
                }

                Log.d("IpRangesViewModel", "Total results found: ${allResults.size}")
                _ipRanges.value = allResults.distinctBy { it.range }

            } catch (e: Exception) {
                Log.e("IpRangesViewModel", "Search error", e)
                _error.value = e.message ?: getApplication<Application>().getString(R.string.unknown_error)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun searchLocalDatabase(
        source: IpRangeSource,
        latitude: Double,
        longitude: Double,
        radius: Double
    ): List<IpRangeResult> {
        return try {
            Log.d("IpRangesViewModel", "Searching local database: ${source.name}")
            val dbItem = source.dbItem ?: return emptyList()

            val helper = SQLite3WiFiHelper(
                getApplication(),
                android.net.Uri.parse(dbItem.path),
                dbItem.directPath
            )

            val ranges = helper.getIpRanges(latitude, longitude, radius)
            helper.close()

            Log.d("IpRangesViewModel", "Local database results: ${ranges.size}")

            ranges.map { range ->
                IpRangeResult(
                    range = range["range"] as? String ?: "",
                    netname = range["netname"] as? String ?: "",
                    description = range["descr"] as? String ?: "",
                    country = range["country"] as? String ?: "",
                    sourceName = source.name
                )
            }
        } catch (e: Exception) {
            Log.e("IpRangesViewModel", "Local database search error for ${source.name}", e)
            emptyList()
        }
    }

    private suspend fun searchApi(
        source: IpRangeSource,
        latitude: Double,
        longitude: Double,
        radius: Double
    ): List<IpRangeResult> {
        return try {
            Log.d("IpRangesViewModel", "Searching API: ${source.name}")
            val dbItem = source.dbItem ?: return emptyList()

            val network = API3WiFiNetwork(
                context = getApplication(),
                serverUrl = dbItem.path,
                connectTimeout = 10000,
                readTimeout = 15000,
                ignoreSSL = false,
                includeAppIdentifier = true,
                apiReadKey = dbItem.apiReadKey ?: "",
                apiWriteKey = dbItem.apiWriteKey
            )

            val request = API3WiFiRequest.ApiRanges(
                key = dbItem.apiReadKey ?: "",
                lat = latitude.toFloat(),
                lon = longitude.toFloat(),
                rad = radius.toFloat()
            )

            val response = network.executeRequest(request, API3WiFiViewModel.RequestType.POST_JSON)
            Log.d("IpRangesViewModel", "API response length: ${response.length}")

            parseApiResponse(response, source.name)
        } catch (e: Exception) {
            Log.e("IpRangesViewModel", "API search error for ${source.name}", e)
            emptyList()
        }
    }

    private fun parseApiResponse(response: String, sourceName: String): List<IpRangeResult> {
        return try {
            Log.d("IpRangesViewModel", "Parsing API response: ${response.take(200)}...")

            val json = JSONObject(response)
            if (!json.optBoolean("result", false)) {
                val error = json.optString("error", "unknown")
                Log.w("IpRangesViewModel", "API returned result=false, error: $error")
                return emptyList()
            }

            val dataArray = json.optJSONArray("data")
            if (dataArray == null) {
                Log.w("IpRangesViewModel", "No data array in API response")
                return emptyList()
            }

            val results = mutableListOf<IpRangeResult>()

            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue

                val range = item.optString("range", "")
                val netname = item.optString("netname", "")
                val description = item.optString("descr", "")
                val country = item.optString("country", "")

                if (range.isNotEmpty()) {
                    results.add(
                        IpRangeResult(
                            range = range,
                            netname = netname,
                            description = description,
                            country = country,
                            sourceName = sourceName
                        )
                    )
                }
            }

            Log.d("IpRangesViewModel", "Parsed ${results.size} ranges from API response")
            results
        } catch (e: Exception) {
            Log.e("IpRangesViewModel", "Error parsing API response", e)
            emptyList()
        }
    }

    fun clearError() {
        _error.value = null
    }
}