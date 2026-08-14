package com.lsd.wififrankenstein.ui.databasefinder

import android.app.Application
import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.network.WpaSecClient
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.wifimap.ExternalIndexManager
import com.lsd.wififrankenstein.util.DatabaseIndices
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        private const val PREFS_NAME = "database_finder_state"
        private const val KEY_SELECTED_SOURCES = "selected_sources"
        private const val KEY_SELECTED_FILTERS = "selected_filters"
        private const val KEY_KNOWN_SOURCES = "known_sources"
        private const val LARGE_DB_THRESHOLD_MB = 1024f
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal val dbSetupViewModel = DbSetupViewModel(application)

    private val wpaSecClient = WpaSecClient(application)

    private val _searchResults = MutableStateFlow<PagingData<SearchResult>>(PagingData.empty())
    val searchResults = _searchResults.asStateFlow()

    private val _wpaSecToast = MutableLiveData<String?>()
    val wpaSecToast: LiveData<String?> = _wpaSecToast

    private val selectedSources = mutableSetOf<String>()
    private val selectedFilters = mutableSetOf(
        FilterType.BSSID,
        FilterType.ESSID
    )

    private val _searchMode = MutableStateFlow(SearchMode.PREFIX)

    private var searchJob: Job? = null
    private var paginationHelper: PaginationHelper? = null
    private var advancedPaginationHelper: AdvancedPaginationHelper? = null
    private var activeSimpleQuery: String? = null
    private var activeAdvancedQuery: AdvancedSearchQuery? = null

    fun setSearchMode(value: SearchMode) {
        _searchMode.value = value
    }

    private fun persistState() {
        val available = getAvailableSources().toSet()
        val pathToId = buildPathToIdMap()
        val savedKnown = prefs.getStringSet(KEY_KNOWN_SOURCES, null) ?: emptySet()
        val known = migrateSavedSourceSet(savedKnown, pathToId, available)
            .toMutableSet()
            .apply { addAll(available) }
        prefs.edit()
            .putStringSet(KEY_SELECTED_SOURCES, selectedSources.toSet())
            .putStringSet(KEY_SELECTED_FILTERS, selectedFilters.map { it.key }.toSet())
            .putStringSet(KEY_KNOWN_SOURCES, known)
            .apply()
    }

    fun saveState() {
        persistState()
    }

    init {
        val savedSources = prefs.getStringSet(KEY_SELECTED_SOURCES, null)
        val savedFilters = prefs.getStringSet(KEY_SELECTED_FILTERS, null)

        viewModelScope.launch {
            dbSetupViewModel.loadDbList()
            val available = getAvailableSources().toSet()
            val pathToId = buildPathToIdMap()
            val known = prefs.getStringSet(KEY_KNOWN_SOURCES, null)
            if (savedSources != null) {
                selectedSources.addAll(migrateSavedSourceSet(savedSources, pathToId, available))
                val knownIds = known?.let { migrateSavedSourceSet(it, pathToId, available) }
                selectedSources.addAll(available.filter { knownIds == null || it !in knownIds })
            }
            if (selectedSources.isEmpty()) {
                selectedSources.addAll(available)
            }
            persistState()
        }

        if (savedFilters != null) {
            val restored = savedFilters.mapNotNull { FilterType.fromKey(it) }
            if (restored.isNotEmpty()) {
                selectedFilters.clear()
                selectedFilters.addAll(restored)
            }
        }
        if (selectedFilters.isEmpty()) {
            selectedFilters.add(FilterType.BSSID)
            selectedFilters.add(FilterType.ESSID)
            Log.d(
                TAG,
                "No saved/valid filters, restored defaults: ${selectedFilters.joinToString()}"
            )
        } else {
            Log.d(TAG, "Initial filters: ${selectedFilters.joinToString()}")
        }
    }

    fun refreshDatabases() {
        viewModelScope.launch {
            try {
                val oldSelectedSources = selectedSources.toSet()

                dbSetupViewModel.loadDbList(force = DbSetupViewModel.needDataRefresh)

                val availableSources = getAvailableSources()
                val pathToId = buildPathToIdMap()
                val removedSources = oldSelectedSources.filter { !availableSources.contains(it) }

                if (removedSources.isNotEmpty()) {
                    Log.d(TAG, "Removed sources detected: $removedSources")
                    selectedSources.removeAll(removedSources)
                }

                val known = prefs.getStringSet(KEY_KNOWN_SOURCES, null)
                val knownIds =
                    known?.let { migrateSavedSourceSet(it, pathToId, availableSources.toSet()) }
                val newSources = availableSources.filter { knownIds == null || it !in knownIds }
                if (newSources.isNotEmpty()) {
                    Log.d(TAG, "New sources detected, auto-enabling: $newSources")
                    selectedSources.addAll(newSources)
                }

                persistState()

                if (removedSources.isNotEmpty()) {
                    _searchResults.value = PagingData.empty()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing databases", e)
            }
        }
    }

    suspend fun getDatabaseIndexInfo(): Map<String, String> {
        return try {
            dbSetupViewModel.dbList.value?.associate { dbItem ->
                Log.d(TAG, "getDatabaseIndexInfo: id=${dbItem.id}, dbType=${dbItem.dbType}")

                val indexLevel = when (dbItem.dbType) {
                    DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                        try {
                            SQLite3WiFiHelper(
                                getApplication(),
                                dbItem.path.toUri(),
                                dbItem.directPath
                            ).use { helper ->
                                val db = helper.database
                                if (db != null) {
                                    DatabaseIndices.determineIndexLevel(db).toString()
                                } else "UNKNOWN"
                            }
                        } catch (e: Exception) {
                            "ERROR"
                        }
                    }

                    DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                        if (dbItem.directPath != null) {
                            val externalIndexManager = ExternalIndexManager(getApplication())
                            externalIndexManager.getIndexLevel(dbItem.id)
                        } else {
                            "NONE"
                        }
                    }

                    DbType.LOCAL_APP_DB -> {
                        LocalAppDbHelper(getApplication()).getIndexLevel()
                    }

                    else -> "N/A"
                }
                dbItem.id to indexLevel
            } ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting database index info", e)
            emptyMap()
        }
    }

    private suspend fun ensureDbListLoaded() {
        if (dbSetupViewModel.dbList.value.isNullOrEmpty()) {
            dbSetupViewModel.loadDbList()
            var attempts = 0
            while (dbSetupViewModel.dbList.value.isNullOrEmpty() && attempts < 5) {
                delay(300)
                attempts++
            }
        }
    }

    fun performSearch(query: String) {
        Log.d(TAG, "Starting search with query: '$query'")
        searchJob?.cancel()
        paginationHelper?.close()
        paginationHelper = null
        advancedPaginationHelper?.close()
        advancedPaginationHelper = null
        activeSimpleQuery = query
        activeAdvancedQuery = null
        searchJob = viewModelScope.launch {
            try {
                ensureDbListLoaded()
                val helper = PaginationHelper(
                    getApplication(),
                    query,
                    dbSetupViewModel.dbList.value ?: emptyList(),
                    selectedSources.toSet(),
                    selectedFilters.toSet(),
                    _searchMode.value
                )
                paginationHelper = helper
                val pagerFlow = Pager(
                    config = PagingConfig(
                        pageSize = 10,
                        enablePlaceholders = false,
                        prefetchDistance = 2,
                        initialLoadSize = 10,
                        maxSize = 100
                    ),
                    pagingSourceFactory = {
                        DatabaseFinderPagingSource(
                            query,
                            helper
                        )
                    }
                ).flow.cachedIn(viewModelScope)

                pagerFlow.collectLatest {
                    _searchResults.value = it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during search: ${e.message}", e)
            }
        }
    }

    fun performAdvancedSearch(advancedQuery: AdvancedSearchQuery) {
        Log.d(TAG, "Starting advanced search: $advancedQuery")
        searchJob?.cancel()
        paginationHelper?.close()
        paginationHelper = null
        advancedPaginationHelper?.close()
        advancedPaginationHelper = null
        activeAdvancedQuery = advancedQuery
        activeSimpleQuery = null
        searchJob = viewModelScope.launch {
            try {
                ensureDbListLoaded()
                val helper = AdvancedPaginationHelper(
                    getApplication(),
                    advancedQuery,
                    dbSetupViewModel.dbList.value ?: emptyList(),
                    selectedSources.toSet()
                )
                advancedPaginationHelper = helper
                val pagerFlow = Pager(
                    config = PagingConfig(
                        pageSize = 10,
                        enablePlaceholders = false,
                        prefetchDistance = 2,
                        initialLoadSize = 10,
                        maxSize = 100
                    ),
                    pagingSourceFactory = {
                        DatabaseFinderAdvancedPagingSource(
                            advancedQuery,
                            helper
                        )
                    }
                ).flow.cachedIn(viewModelScope)

                pagerFlow.collectLatest {
                    _searchResults.value = it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during advanced search: ${e.message}", e)
            }
        }
    }

    fun isSlowSearchPotential(): Boolean {
        if (_searchMode.value != SearchMode.SUBSTRING) return false
        val dbItems = dbSetupViewModel.dbList.value ?: return false
        return selectedSources.any { id ->
            val item = dbItems.find { it.id == id } ?: return@any false
            val isFileDb = item.dbType == DbType.SQLITE_FILE_P3WIFI ||
                    item.dbType == DbType.SQLITE_FILE_CUSTOM ||
                    item.dbType == DbType.SMARTLINK_SQLITE_FILE_P3WIFI ||
                    item.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM
            isFileDb && maxOf(item.originalSizeInMB, item.cachedSizeInMB) > LARGE_DB_THRESHOLD_MB
        }
    }

    fun cancelSearch() {
        Log.d(TAG, "Cancelling active search")
        activeSimpleQuery = null
        activeAdvancedQuery = null
        searchJob?.cancel()
        searchJob = null
        paginationHelper?.cancelSearch()
        paginationHelper?.close()
        paginationHelper = null
        advancedPaginationHelper?.close()
        advancedPaginationHelper = null
        _searchResults.value = PagingData.empty()
    }

    fun getAvailableSources(): List<String> {
        val dbSources = dbSetupViewModel.dbList.value?.map { it.id } ?: emptyList()
        val extraSources = mutableListOf<String>()
        extraSources.add(getApplication<Application>().getString(R.string.source_inapp_database))
        extraSources.add(getApplication<Application>().getString(R.string.handshake_storage))
        return dbSources + extraSources
    }

    private fun buildPathToIdMap(): Map<String, String> {
        return dbSetupViewModel.dbList.value?.associate { it.path to it.id } ?: emptyMap()
    }

    private fun migrateSavedSourceSet(
        saved: Set<String>,
        pathToId: Map<String, String>,
        available: Set<String>
    ): Set<String> {
        return saved.mapNotNull { value ->
            when {
                value in available -> value
                pathToId[value]?.let { it in available } == true -> pathToId[value]
                else -> null
            }
        }.toSet()
    }

    fun getSelectedSources(): List<String> = selectedSources.toList()

    fun setSourceSelected(source: String, isSelected: Boolean) {
        if (isSelected) selectedSources.add(source) else selectedSources.remove(source)
        persistState()
        rerunActiveSearch()
    }

    private fun rerunActiveSearch() {
        activeSimpleQuery?.let { performSearch(it) }
            ?: activeAdvancedQuery?.let { performAdvancedSearch(it) }
    }

    fun getDetailData(searchResult: SearchResult): Flow<Map<String, Any?>> = flow {
        Log.d(
            TAG,
            "Loading details for BSSID: ${searchResult.bssid}, source: ${searchResult.source}"
        )

        if (dbSetupViewModel.dbList.value.isNullOrEmpty()) {
            Log.d(TAG, "Database list not loaded yet, waiting for data...")
            emit(mapOf("message" to getApplication<Application>().getString(R.string.df_loading_db_list)))

            dbSetupViewModel.loadDbList()

            var waitAttempts = 0
            while (dbSetupViewModel.dbList.value.isNullOrEmpty() && waitAttempts < 5) {
                delay(300)
                waitAttempts++
            }

            if (dbSetupViewModel.dbList.value.isNullOrEmpty()) {
                Log.e(TAG, "Failed to load database list after waiting")
                emit(mapOf("error" to getApplication<Application>().getString(R.string.df_failed_load_db_list)))
                return@flow
            }
        }

        val dbItem = dbSetupViewModel.dbList.value?.find { it.id == searchResult.source }

        if (dbItem == null) {
            val app = getApplication<Application>()
            if (searchResult.source == app.getString(R.string.source_inapp_database)) {
                LocalAppDetailLoader(app, searchResult.bssid).loadDetailData(searchResult)
                    .collect { details ->
                        emit(details)
                    }
                return@flow
            }
            if (searchResult.source == app.getString(R.string.handshake_storage)) {
                emit(
                    mapOf(
                        "ESSID" to searchResult.ssid,
                        "BSSID" to searchResult.getFormattedBssid()
                    )
                )
                return@flow
            }
            Log.e(TAG, "Database not found for source: ${searchResult.source}")
            emit(mapOf("error" to app.getString(R.string.df_database_not_found)))
            return@flow
        }

        emit(mapOf("message" to getApplication<Application>().getString(R.string.df_loading_data)))

        Log.d(TAG, "getDetailData: dbType=${dbItem.dbType}, source=${searchResult.source}")

        val detailLoader = when (dbItem.dbType) {
            DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> WiFi3DetailLoader(
                getApplication(),
                dbItem,
                searchResult.bssid
            )

            DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> CustomDbDetailLoader(
                getApplication(),
                dbItem,
                searchResult.bssid
            )

            DbType.LOCAL_APP_DB -> LocalAppDetailLoader(getApplication(), searchResult.bssid)
            DbType.WIFI_API -> ApiDetailLoader(getApplication(), dbItem, searchResult.bssid)
            else -> {
                Log.e(TAG, "Unsupported database type: ${dbItem.dbType}")
                emit(mapOf("error" to getApplication<Application>().getString(R.string.df_unsupported_db_type)))
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

    fun getSelectedFilters(): List<FilterType> = selectedFilters.toList()

    fun setFilterSelected(filter: FilterType, isSelected: Boolean) {
        if (isSelected) selectedFilters.add(filter) else selectedFilters.remove(filter)
        persistState()
        rerunActiveSearch()
    }

    fun checkOnWpaSec(item: SearchResult) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val message = try {
                val found = wpaSecClient.checkPasswordByBssidSsid(
                    wpaSecClient.bssidToHex(item.getFormattedBssid()),
                    wpaSecClient.essidToHex(item.ssid)
                )
                if (found) app.getString(R.string.wpasec_password_found)
                else app.getString(R.string.wpasec_not_found)
            } catch (e: Exception) {
                app.getString(R.string.wpasec_error, e.message)
            }
            _wpaSecToast.postValue(message)
        }
    }

    fun clearWpaSecToast() {
        _wpaSecToast.value = null
    }

    override fun onCleared() {
        paginationHelper?.close()
        paginationHelper = null
        advancedPaginationHelper?.close()
        advancedPaginationHelper = null
        super.onCleared()
    }
}