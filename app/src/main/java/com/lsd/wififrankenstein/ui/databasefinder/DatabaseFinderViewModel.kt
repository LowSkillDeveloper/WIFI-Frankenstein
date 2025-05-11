package com.lsd.wififrankenstein.ui.databasefinder

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

class DatabaseFinderViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DatabaseFinderVM"
    }

    internal val dbSetupViewModel = DbSetupViewModel(application)

    private val _searchResults = MutableStateFlow<PagingData<SearchResult>>(PagingData.empty())
    val searchResults = _searchResults.asStateFlow()

    private val _isSearching = MutableLiveData<Boolean>()
    val isSearching: LiveData<Boolean> = _isSearching

    private val selectedSources = mutableSetOf<String>()
    private val selectedFilters = mutableSetOf<Int>()

    private val _searchWholeWords = MutableStateFlow(false)

    fun setSearchWholeWords(value: Boolean) {
        _searchWholeWords.value = value
    }


    init {
        viewModelScope.launch {
            dbSetupViewModel.loadDbList()
            selectedSources.addAll(getAvailableSources())
        }
        _searchWholeWords.value = false
        selectedFilters.addAll(listOf(
            R.string.filter_bssid,
            R.string.filter_essid
        ))
        Log.d(TAG, "Initial filters: ${selectedFilters.joinToString()}")
    }

    fun refreshDatabases() {
        viewModelScope.launch {
            try {
                val oldSelectedSources = selectedSources.toSet()

                dbSetupViewModel.loadDbList()

                val availableSources = getAvailableSources()
                val removedSources = oldSelectedSources.filter { !availableSources.contains(it) }

                if (removedSources.isNotEmpty()) {
                    Log.d(TAG, "Removed sources detected: $removedSources")
                    selectedSources.removeAll(removedSources)
                }

                if (removedSources.isNotEmpty()) {
                    _searchResults.value = PagingData.empty()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing databases", e)
            }
        }
    }

    fun performSearch(query: String) {
        Log.d(TAG, "Starting search with query: '$query'")
        System.currentTimeMillis()

        viewModelScope.launch {
            _isSearching.value = true
            try {
                val pagerFlow = Pager(
                    config = PagingConfig(
                        pageSize = 50,
                        enablePlaceholders = false,
                        prefetchDistance = 2,
                        initialLoadSize = 50
                    ),
                    pagingSourceFactory = {
                        DatabaseFinderPagingSource(
                            getApplication(),
                            query,
                            dbSetupViewModel.dbList.value ?: emptyList(),
                            selectedSources,
                            selectedFilters,
                            _searchWholeWords.value
                        )
                    }
                ).flow.cachedIn(viewModelScope)

                pagerFlow.collectLatest {
                    _searchResults.value = it
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during search: ${e.message}", e)
            }
        }
    }

    fun getAvailableSources(): List<String> {
        return dbSetupViewModel.dbList.value?.map { it.path } ?: emptyList()
    }

    fun getSelectedSources(): List<String> = selectedSources.toList()

    fun setSourceSelected(source: String, isSelected: Boolean) {
        if (isSelected) selectedSources.add(source) else selectedSources.remove(source)
    }

    fun getDetailData(searchResult: SearchResult): Flow<Map<String, Any?>> = flow {
        Log.d(TAG, "Loading details for BSSID: ${searchResult.bssid}, source: ${searchResult.source}")

        if (dbSetupViewModel.dbList.value.isNullOrEmpty()) {
            Log.d(TAG, "Database list not loaded yet, waiting for data...")
            emit(mapOf("message" to "Загрузка списка баз данных..."))

            dbSetupViewModel.loadDbList()

            var waitAttempts = 0
            while (dbSetupViewModel.dbList.value.isNullOrEmpty() && waitAttempts < 5) {
                delay(300)
                waitAttempts++
            }

            if (dbSetupViewModel.dbList.value.isNullOrEmpty()) {
                Log.e(TAG, "Failed to load database list after waiting")
                emit(mapOf("error" to "Не удалось загрузить список баз данных"))
                return@flow
            }
        }

        val dbItem = dbSetupViewModel.dbList.value?.find { it.path == searchResult.source }

        if (dbItem == null) {
            Log.e(TAG, "Database not found for source: ${searchResult.source}")
            emit(mapOf("error" to "База данных не найдена"))
            return@flow
        }

        emit(mapOf("message" to "Загрузка данных..."))

        val detailLoader = when (dbItem.dbType) {
            DbType.SQLITE_FILE_3WIFI -> WiFi3DetailLoader(getApplication(), dbItem, searchResult.bssid)
            DbType.SQLITE_FILE_CUSTOM -> CustomDbDetailLoader(getApplication(), dbItem, searchResult.bssid)
            DbType.LOCAL_APP_DB -> LocalAppDetailLoader(getApplication(), searchResult.bssid)
            DbType.WIFI_API -> ApiDetailLoader(getApplication(), dbItem, searchResult.bssid)
            else -> {
                Log.e(TAG, "Unsupported database type: ${dbItem.dbType}")
                emit(mapOf("error" to "Неподдерживаемый тип базы данных"))
                return@flow
            }
        }

        detailLoader.loadDetailData(searchResult).collect { details ->

            emit(details)
        }
    }.catch { e ->
        Log.e(TAG, "Error loading details", e)
        emit(mapOf("error" to e.message))
    }

    fun getSelectedFilters(): List<Int> = selectedFilters.toList()

    fun setFilterSelected(filter: Int, isSelected: Boolean) {
        if (isSelected) selectedFilters.add(filter) else selectedFilters.remove(filter)
    }
}