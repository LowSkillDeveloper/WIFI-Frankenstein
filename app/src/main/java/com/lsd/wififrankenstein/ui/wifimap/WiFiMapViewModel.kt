package com.lsd.wififrankenstein.ui.wifimap

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.databasefinder.SearchMode
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.MapHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.ThreeWifiAppMapHelper
import com.lsd.wififrankenstein.ui.dbsetup.ThreeWifiDevMapHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeMetadataDbHelper
import com.lsd.wififrankenstein.ui.ipranges.IpRangeResult
import com.lsd.wififrankenstein.util.AdvancedCache
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.PerformanceManager
import com.lsd.wififrankenstein.util.QuadkeyUtils
import com.lsd.wififrankenstein.util.TileRange
import com.lsd.wififrankenstein.util.toTileGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.osmdroid.util.BoundingBox

class WiFiMapViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val HEX_PAIR_REGEX = Regex("(.{2})")

        private const val SELECTED_DATABASE_IDS_KEY = "map_selected_db_ids"
    }

    private val TAG = "WiFiMapViewModel"
    private val dbSetupViewModel = DbSetupViewModel(application)
    private val settingsPrefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _points = MutableLiveData<List<MapPoint>>()
    val points: LiveData<List<MapPoint>> = _points

    private val _selectedPoint = MutableLiveData<NetworkPoint>()
    val selectedPoint: LiveData<NetworkPoint> = _selectedPoint

    private val _availableDatabases = MutableLiveData<List<DbItem>>()
    val availableDatabases: LiveData<List<DbItem>> = _availableDatabases

    private val _loadingProgress = MutableLiveData<Int>()
    val loadingProgress: LiveData<Int> = _loadingProgress

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _ipRanges = MutableLiveData<List<IpRangeResult>>()
    val ipRanges: LiveData<List<IpRangeResult>> = _ipRanges

    private val databaseHelpers = mutableMapOf<String, SQLiteOpenHelper>()

    private val mapHelpers = mutableMapOf<String, MapHelper>()

    private val _showIndexingDialog = MutableLiveData<DbItem?>()
    val showIndexingDialog: LiveData<DbItem?> = _showIndexingDialog

    private val _indexingProgress = MutableLiveData<Int>()
    val indexingProgress: LiveData<Int> = _indexingProgress

    data class CorruptionEvent(
        val dbItem: DbItem,
        val canRecover: Boolean,
        val sourcePath: String
    )

    private val _corruptionEvent = MutableLiveData<CorruptionEvent?>()
    val corruptionEvent: LiveData<CorruptionEvent?> = _corruptionEvent

    private val corruptionNotified = mutableSetOf<String>()

    private val externalIndexManager = ExternalIndexManager(getApplication())

    private val _addReadOnlyDb = MutableLiveData<DbItem>()

    private fun addLocalDbIfMissing(databases: List<DbItem>): List<DbItem> {
        var result = databases
        if (result.none { it.dbType == DbType.LOCAL_APP_DB }) {
            result = result + getLocalDbItem()
        }
        if (result.none { it.dbType == DbType.HANDSHAKE_STORAGE }) {
            result = result + getHandshakeStorageItem()
        }
        return result
    }

    private val dbListObserver = Observer<List<DbItem>?> { dbList ->
        val databases = addLocalDbIfMissing(dbList ?: emptyList())
        _availableDatabases.value = databases
        assignColorsToDatabase(databases)
        viewModelScope.launch {
            databases.filter { it.dbType == DbType.WIFI_API && !it.supportsMapApi }
                .forEach { database ->
                    checkDatabaseMapSupport(database)
                }
        }
    }
    val addReadOnlyDb: LiveData<DbItem> = _addReadOnlyDb

    private val localDbIndexManager = LocalDbIndexManager(getApplication())

    private var lastUpdateTime = 0L
    private var currentLoadingJob: Job? = null
    private var ipRangeSearchJob: Job? = null
    private var isLoadingPoints = false

    var enableRdapEnrichment: Boolean
        get() = settingsPrefs.getBoolean("map_rdap_enrichment", false)
        set(value) = settingsPrefs.edit { putBoolean("map_rdap_enrichment", value) }

    var enableIpRangeCounts: Boolean
        get() = settingsPrefs.getBoolean("map_ip_range_counts", false)
        set(value) = settingsPrefs.edit { putBoolean("map_ip_range_counts", value) }

    var searchRadius: Float
        get() = settingsPrefs.getFloat("map_search_radius", 5f)
        set(value) = settingsPrefs.edit { putFloat("map_search_radius", value) }

    var showRadiusCircle: Boolean
        get() = settingsPrefs.getBoolean("map_show_radius_circle", false)
        set(value) = settingsPrefs.edit { putBoolean("map_show_radius_circle", value) }
    private val _selectedDatabaseIds = mutableSetOf<String>()
    val selectedDatabaseIds: Set<String> get() = _selectedDatabaseIds.toSet()

    fun setSelectedDatabaseIds(ids: Set<String>) {
        _selectedDatabaseIds.clear()
        _selectedDatabaseIds.addAll(ids)
        settingsPrefs.edit { putStringSet(SELECTED_DATABASE_IDS_KEY, ids) }
    }

    fun getSavedSelectedDatabaseIds(): Set<String> {
        return settingsPrefs.getStringSet(SELECTED_DATABASE_IDS_KEY, emptySet())
            .orEmpty()
            .filterTo(mutableSetOf()) { it.isNotBlank() }
    }

    var currentBoundingBox: BoundingBox? = null
    private var currentZoom: Double = -1.0
    private var currentTileRange: TileRange? = null

    private val _pointsLoaded = MutableLiveData<Boolean>()
    val pointsLoaded: LiveData<Boolean> = _pointsLoaded

    private val MIN_UPDATE_INTERVAL = 300L

    private val _databaseColors = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val availableColors = listOf(
        Color.rgb(65, 105, 225),
        Color.rgb(34, 139, 34),
        Color.rgb(220, 20, 60),
        Color.rgb(138, 43, 226),
        Color.rgb(70, 130, 180),
        Color.rgb(107, 142, 35),
        Color.rgb(112, 128, 144),
        Color.rgb(30, 144, 255),
        Color.rgb(148, 0, 211),
        Color.rgb(178, 34, 34),
        Color.rgb(32, 178, 170),
        Color.rgb(218, 165, 32),
        Color.rgb(139, 69, 19),
        Color.rgb(205, 92, 92),
        Color.rgb(72, 209, 204),
        Color.rgb(255, 140, 0),
        Color.rgb(147, 112, 219),
        Color.rgb(60, 179, 113),
        Color.rgb(255, 99, 71),
        Color.rgb(100, 149, 237),
        Color.rgb(50, 205, 50),
        Color.rgb(255, 20, 147),
        Color.rgb(0, 191, 255),
        Color.rgb(186, 85, 211),
        Color.rgb(255, 215, 0),
        Color.rgb(95, 158, 160),
        Color.rgb(240, 128, 128),
        Color.rgb(221, 160, 221),
        Color.rgb(175, 238, 238)
    )
    private var nextColorIndex = 0

    private val _databaseColorsLiveData = MutableLiveData<Map<String, Int>>()
    val databaseColors: LiveData<Map<String, Int>> = _databaseColorsLiveData

    private fun assignColorsToDatabase(databases: List<DbItem>) {
        databases.forEach { database ->
            if (!_databaseColors.containsKey(database.id)) {
                val color = availableColors[nextColorIndex % availableColors.size]
                _databaseColors[database.id] = color
                nextColorIndex++
                Log.d(TAG, "Assigned color to database ${database.id}: $color")
            }
        }
        _databaseColorsLiveData.postValue(_databaseColors.toMap())
    }

    fun getColorForDatabase(databaseId: String): Int {
        return _databaseColors[databaseId] ?: Color.GRAY
    }

    fun getMinZoomForMarkers(): Double {
        val zoomSetting = settingsPrefs.getFloat("map_marker_visibility_zoom", 11f)
        val result = zoomSetting.toDouble().coerceIn(1.0, 18.0)
        Log.d(TAG, "getMinZoomForMarkers: setting=$zoomSetting, result=$result")
        return result
    }

    init {
        dbSetupViewModel.dbList.observeForever(dbListObserver)
        viewModelScope.launch { reloadAvailableDatabases() }
    }

    private fun getHelper(database: DbItem): SQLiteOpenHelper? {
        Log.d(TAG, "Getting helper for database: ${database.id}")

        val existing = databaseHelpers[database.id]
        if (existing is SQLite3WiFiHelper && existing.corruptionDetected) {
            Log.d(TAG, "Helper for ${database.id} has corruption flag, not using it")
            return null
        }

        return databaseHelpers.getOrPut(database.id) {
            Log.d(TAG, "Creating new helper for database: ${database.id}")
            Log.d(TAG, "getHelper: database=${database.id}, dbType=${database.dbType}")
            val helper = when (database.dbType) {
                DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                    Log.d(TAG, "Creating SQLiteCustomHelper for database: ${database.id}")
                    SQLiteCustomHelper(getApplication(), database.path.toUri(), database.directPath)
                }

                DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                    Log.d(TAG, "Creating SQLite3WiFiHelper for database: ${database.id}")
                    SQLite3WiFiHelper(getApplication(), database.path.toUri(), database.directPath)
                }

                else -> {
                    Log.d(TAG, "Creating SQLite3WiFiHelper for database: ${database.id}")
                    SQLite3WiFiHelper(getApplication(), database.path.toUri(), database.directPath)
                }
            }

            if (helper is SQLite3WiFiHelper && helper.corruptionDetected && helper.database == null) {
                Log.d(TAG, "Corruption detected for ${database.id}, posting event")
                databaseHelpers.remove(database.id)

                if (database.id !in corruptionNotified) {
                    corruptionNotified.add(database.id)
                    val canRecover = checkSourceAvailability(database)
                    _corruptionEvent.postValue(
                        CorruptionEvent(database, canRecover, database.path)
                    )
                }
                return@getOrPut helper
            }

            if (helper is SQLite3WiFiHelper && helper.database == null) {
                Log.e(TAG, "Helper for ${database.id} has null database but no corruption flag")
                databaseHelpers.remove(database.id)
            }

            helper
        }
    }

    private fun checkSourceAvailability(database: DbItem): Boolean {
        return try {
            val uri = database.path.toUri()
            val app = getApplication<Application>()
            app.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Source not available for ${database.id}", e)
            false
        }
    }

    fun attemptRecovery(database: DbItem) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Attempting recovery for database: ${database.id}")
                corruptionNotified.remove(database.id)
                databaseHelpers.remove(database.id)

                val helper = SQLite3WiFiHelper(
                    getApplication(),
                    database.path.toUri(),
                    database.directPath
                )

                if (helper.database != null) {
                    Log.d(TAG, "Database recovery successful for ${database.id}")
                    databaseHelpers[database.id] = helper
                    reloadAvailableDatabases()
                    _corruptionEvent.postValue(null)
                    _error.postValue(
                        getApplication<Application>().getString(R.string.db_recovery_success)
                    )
                } else {
                    if (helper.corruptionDetected) {
                        _error.postValue(
                            getApplication<Application>().getString(R.string.db_recovery_failed)
                        )
                    }
                    _corruptionEvent.postValue(
                        CorruptionEvent(database, checkSourceAvailability(database), database.path)
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recovery failed for ${database.id}", e)
                _error.postValue(
                    getApplication<Application>().getString(R.string.db_recovery_failed) + ": " + e.message
                )
                _corruptionEvent.postValue(
                    CorruptionEvent(database, checkSourceAvailability(database), database.path)
                )
            }
        }
    }

    fun deleteCorruptedDatabase(database: DbItem) {
        Log.d(TAG, "Deleting corrupted database: ${database.id}")
        corruptionNotified.remove(database.id)
        databaseHelpers.remove(database.id)
        dbSetupViewModel.removeDb(database.id)
        _corruptionEvent.postValue(null)
        reloadAvailableDatabases()
    }

    fun getPreventClusterMerge(): Boolean {
        return settingsPrefs.getBoolean("map_prevent_cluster_merge", false)
    }

    fun setPreventClusterMerge(value: Boolean) {
        settingsPrefs.edit { putBoolean("map_prevent_cluster_merge", value) }
    }

    private fun getMaxPointsForZoom(zoom: Double): Int {
        return PerformanceManager.getMaxPointsPerOperation()
    }

    suspend fun checkDatabaseMapSupport(dbItem: DbItem): Boolean {
        if (dbItem.dbType != DbType.WIFI_API) return false

        if (dbItem.apiProtocol != null && mapHelpers.containsKey(dbItem.id)) return true

        return withContext(Dispatchers.IO) {
            try {
                val appHelper = ThreeWifiAppMapHelper(
                    getApplication(),
                    dbItem.path,
                    dbItem.jwtToken
                )
                var supportsMap = appHelper.checkMapSupport()

                if (supportsMap) {
                    appHelper.setJwtToken(dbItem.jwtToken)
                    mapHelpers[dbItem.id] = appHelper
                    updateDbItemApiProtocol(dbItem, "3wifi_app", supportsMap)
                    Log.d(TAG, "Database ${dbItem.id} is 3wifi.app")
                } else {
                    val devHelper = ThreeWifiDevMapHelper(
                        getApplication(),
                        dbItem.path,
                        dbItem.apiReadKey ?: "000000000000"
                    )
                    supportsMap = devHelper.checkMapSupport()
                    if (supportsMap) {
                        mapHelpers[dbItem.id] = devHelper
                        updateDbItemApiProtocol(dbItem, "3wifi_dev", supportsMap)
                        Log.d(TAG, "Database ${dbItem.id} is 3wifi.dev")
                    }
                }

                supportsMap
            } catch (e: Exception) {
                Log.e(TAG, "Error checking map support for ${dbItem.id}", e)
                false
            }
        }
    }

    private fun updateDbItemApiProtocol(dbItem: DbItem, protocol: String, supportsMap: Boolean) {
        val currentList = _availableDatabases.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == dbItem.id }
        if (index != -1) {
            currentList[index] = currentList[index].copy(
                supportsMapApi = supportsMap,
                apiProtocol = protocol
            )
            _availableDatabases.postValue(currentList)
        }
    }

    fun handleCustomDbSelection(
        dbItem: DbItem,
        isSelected: Boolean,
        selectedDatabases: Set<DbItem>
    ) {
        Log.d(TAG, "Handle DB selection: ${dbItem.id}, isSelected: $isSelected")

        if (isSelected) {
            if ((dbItem.dbType == DbType.LOCAL_APP_DB || dbItem.dbType == DbType.HANDSHAKE_STORAGE) &&
                selectedDatabases.any { db -> db.dbType == dbItem.dbType }
            ) {
                Log.d(TAG, "${dbItem.dbType} already selected, skipping")
                return
            }

            Log.d(TAG, "handleCustomDbSelection: dbType=${dbItem.dbType}, id=${dbItem.id}")
            when (dbItem.dbType) {
                DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                    try {
                        if (dbItem.tableName.isNullOrEmpty() || dbItem.columnMap.isNullOrEmpty() || dbItem.directPath.isNullOrEmpty()) {
                            Log.e(TAG, "Missing required fields in DbItem")
                            _error.postValue(getApplication<Application>().getString(R.string.column_mapping_missing))
                            return
                        }

                        viewModelScope.launch {
                            if (externalIndexManager.needsIndexing(dbItem.id, dbItem.directPath)) {
                                Log.d(TAG, "Database needs external indexing, showing dialog")
                                _showIndexingDialog.value = dbItem
                            } else {
                                Log.d(TAG, "External indexes exist, adding database")
                                _addReadOnlyDb.postValue(dbItem)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking database for external indexing", e)
                        _error.postValue(getApplication<Application>().getString(R.string.db_check_error))
                    }
                }

                DbType.LOCAL_APP_DB -> {
                    try {
                        viewModelScope.launch {
                            val dbHelper = LocalAppDbHelper(getApplication())
                            dbHelper.readableDatabase.use { db ->
                                if (localDbIndexManager.needsIndexing(db)) {
                                    Log.d(TAG, "Local database needs indexing, showing dialog")
                                    _showIndexingDialog.value = dbItem
                                } else {
                                    Log.d(TAG, "Local database indexes exist, adding database")
                                    _addReadOnlyDb.postValue(dbItem)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking local database for indexing", e)
                        _error.postValue(getApplication<Application>().getString(R.string.db_check_error))
                    }
                }

                DbType.HANDSHAKE_STORAGE -> {
                    _addReadOnlyDb.postValue(dbItem)
                }

                DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI -> {
                    _addReadOnlyDb.postValue(dbItem)
                }

                else -> {
                    _addReadOnlyDb.postValue(dbItem)
                }
            }
        }
    }

    suspend fun createLocalDbIndexes() {
        val dbHelper = LocalAppDbHelper(getApplication())
        try {
            dbHelper.writableDatabase.use { db ->
                val success = localDbIndexManager.createIndexes(db) { progress ->
                    Log.d(TAG, "Local database index creation progress: $progress%")
                    _indexingProgress.postValue(progress)
                }

                if (success) {
                    Log.d(TAG, "Successfully created indexes for local database")
                    _addReadOnlyDb.postValue(getLocalDbItem())
                } else {
                    Log.e(TAG, "Failed to create indexes for local database")
                    _error.postValue(getApplication<Application>().getString(R.string.indexing_failed))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating indexes for local database", e)
            _error.postValue(getApplication<Application>().getString(R.string.indexing_failed) + ": " + e.message)
        } finally {
            dbHelper.close()
        }
    }

    suspend fun createCustomDbIndexes(dbItem: DbItem) {
        Log.d(TAG, "Starting to create external indexes for ${dbItem.id}")

        try {
            if (dbItem.tableName.isNullOrEmpty()) {
                Log.e(TAG, "Table name is null or empty for ${dbItem.id}")
                _error.postValue(getApplication<Application>().getString(R.string.indexing_failed))
                return
            }

            if (dbItem.columnMap.isNullOrEmpty()) {
                Log.e(TAG, "Column map is null or empty for ${dbItem.id}")
                _error.postValue(getApplication<Application>().getString(R.string.indexing_failed))
                return
            }

            if (dbItem.directPath.isNullOrEmpty()) {
                Log.e(TAG, "Direct path is null or empty for ${dbItem.id}")
                _error.postValue(getApplication<Application>().getString(R.string.indexing_failed))
                return
            }

            Log.d(TAG, "All prerequisites met, creating external indexes for ${dbItem.id}")
            Log.d(TAG, "Column map: ${dbItem.columnMap}")
            Log.d(TAG, "Table name: ${dbItem.tableName}")
            Log.d(TAG, "Direct path: ${dbItem.directPath}")

            val success = externalIndexManager.createExternalIndexes(
                dbItem.id,
                dbItem.directPath,
                dbItem.tableName,
                dbItem.columnMap
            ) { progress ->
                Log.d(TAG, "Index creation progress for ${dbItem.id}: $progress%")
                _indexingProgress.postValue(progress)
            }

            if (success) {
                Log.d(TAG, "Successfully created external indexes for ${dbItem.id}")
                _addReadOnlyDb.postValue(dbItem)
            } else {
                Log.e(TAG, "Failed to create external indexes for ${dbItem.id}")
                val hasGeoData = dbItem.columnMap?.containsKey("latitude") == true &&
                        dbItem.columnMap?.containsKey("longitude") == true
                val errorMessage = if (!hasGeoData) {
                    getApplication<Application>().getString(R.string.indexing_no_geo_warning)
                } else {
                    getApplication<Application>().getString(R.string.indexing_failed)
                }
                _error.postValue(errorMessage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating external indexes for ${dbItem.id}", e)
            _error.postValue(getApplication<Application>().getString(R.string.indexing_failed) + ": " + e.message)
        }
    }

    suspend fun loadPointsInBoundingBox(
        boundingBox: BoundingBox,
        zoom: Double,
        selectedDatabases: Set<DbItem>,
        scatterMode: Boolean = false,
        expandedBounds: BoundingBox? = null
    ) {
        val filterBounds = expandedBounds ?: boundingBox
        currentBoundingBox = filterBounds
        val pruneBounds = expandedBounds ?: padBoundingBox(boundingBox)

        val previousZoom = currentZoom
        currentZoom = zoom

        val tileZoom = kotlin.math.ceil(zoom).toInt()
        val visibleArea =
            TileBasedQueryEngine.calculateVisibleTilesWithPadding(boundingBox, tileZoom)
        if (visibleArea != null) {
            currentTileRange = visibleArea.tileRange
        } else {
            currentTileRange = null
        }

        val zoomLevelChanged = previousZoom.toInt() != tileZoom

        if (isLoadingPoints) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < MIN_UPDATE_INTERVAL) {
            return
        }
        lastUpdateTime = currentTime

        val minZoom = getMinZoomForMarkers()
        if (zoom < minZoom) {
            _points.value = emptyList()
            return
        }

        currentLoadingJob?.cancel()

        val queryTileRange =
            currentTileRange ?: TileBasedQueryEngine.calculateVisibleTiles(boundingBox, tileZoom)

        currentLoadingJob = viewModelScope.launch {
            isLoadingPoints = true
            try {
                _loadingProgress.postValue(1)

                val allPoints = withContext(MapOperationExecutor.databaseDispatcher) {
                    val pointsLock = Mutex()
                    val points = mutableListOf<MapPoint>()

                    val dbLoadStart = System.currentTimeMillis()
                    val databaseJobs = selectedDatabases.mapIndexed { index, database ->
                        async {
                            if (!isActive) return@async

                            try {
                                val startTime = System.currentTimeMillis()

                                if (database.supportsMapApi && database.dbType == DbType.WIFI_API) {
                                    val networkPoints =
                                        loadMapApiPoints(database, boundingBox, zoom)
                                    if (!isActive) return@async
                                    val mapPoints = networkPoints.map { np ->
                                        MapPoint(
                                            bssidDecimal = np.bssidDecimal,
                                            latitude = np.latitude,
                                            longitude = np.longitude,
                                            color = np.color,
                                            clusterCount = np.clusterCount,
                                            isCluster = np.isCluster,
                                            databaseId = np.databaseId
                                        )
                                    }
                                    pointsLock.withLock { points.addAll(mapPoints) }
                                } else if (queryTileRange == null) {
                                    val boundsPoints = loadClusteredPointsFromDatabase(
                                        database,
                                        boundingBox,
                                        null,
                                        tileZoom,
                                        zoom,
                                        scatterMode
                                    )
                                    val mapPoints = toMapPoints(boundsPoints, database)
                                    pointsLock.withLock { points.addAll(mapPoints) }
                                } else {
                                    val gridSize = getGridSize(tileZoom)
                                    val tileGroups = queryTileRange.toTileGroups(gridSize)

                                    val missedGroups = mutableListOf<TileRange>()

                                    for (group in tileGroups) {
                                        val cacheKey = TileCacheKey(
                                            database.id,
                                            group.minX,
                                            group.minY,
                                            tileZoom
                                        )
                                        val cached = tilePointsCache.get(cacheKey)
                                        if (cached != null) {
                                            pointsLock.withLock { points.addAll(cached) }
                                        } else {
                                            missedGroups.add(group)
                                        }
                                    }

                                    if (missedGroups.isNotEmpty()) {
                                        val groupJobs = missedGroups.map { group ->
                                            async {
                                                val groupBounds =
                                                    QuadkeyUtils.getTileRangeBounds(group, tileZoom)
                                                val groupPoints = loadClusteredPointsFromDatabase(
                                                    database,
                                                    groupBounds,
                                                    group,
                                                    tileZoom,
                                                    zoom,
                                                    scatterMode
                                                )

                                                val mapPoints = toMapPoints(groupPoints, database)

                                                val cacheKey = TileCacheKey(
                                                    database.id,
                                                    group.minX,
                                                    group.minY,
                                                    tileZoom
                                                )
                                                tilePointsCache.put(cacheKey, mapPoints)
                                                mapPoints
                                            }
                                        }

                                        val loadedGroups = groupJobs.awaitAll()
                                        pointsLock.withLock {
                                            for (groupPoints in loadedGroups) {
                                                if (groupPoints != null) {
                                                    points.addAll(groupPoints)
                                                }
                                            }
                                        }
                                    }
                                }

                                val progress = ((index + 1) * 60) / selectedDatabases.size
                                _loadingProgress.postValue(progress)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error loading points from database ${database.id}", e)
                            }
                        }
                    }

                    databaseJobs.awaitAll()
                    points.toList()
                }

                if (!isActive) return@launch
                _loadingProgress.postValue(70)

                val displayPoints = if (zoomLevelChanged) {
                    deduplicatePoints(allPoints)
                } else {
                    val existingPoints = _points.value ?: emptyList()
                    val prunedExisting = prunePointsToBounds(existingPoints, pruneBounds)
                    mergePoints(prunedExisting, allPoints)
                }

                withContext(MapOperationExecutor.uiUpdateDispatcher) {
                    _points.postValue(displayPoints)
                    _pointsLoaded.postValue(true)
                    _loadingProgress.postValue(100)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Load points cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading points", e)
                _error.postValue(
                    getApplication<Application>().getString(
                        R.string.wm_error_loading_map,
                        e.localizedMessage
                    )
                )
            } finally {
                isLoadingPoints = false
                _loadingProgress.postValue(100)
            }
        }
    }

    private data class TileCacheKey(
        val databaseId: String,
        val groupX: Int,
        val groupY: Int,
        val zoom: Int
    )

    private val tilePointsCache = AdvancedCache.create<TileCacheKey, List<MapPoint>>()

    private fun getGridSize(zoom: Int): Int = when {
        zoom < 13 -> 8
        zoom < 15 -> 4
        zoom < 17 -> 2
        else -> 1
    }

    private fun toMapPoints(points: List<ClusteredMapPoint>, database: DbItem): List<MapPoint> {
        val color = getColorForDatabase(database.id)
        return points.map { cmp ->
            MapPoint(
                bssidDecimal = cmp.bssidDecimal,
                latitude = cmp.latitude,
                longitude = cmp.longitude,
                color = color,
                clusterCount = cmp.count,
                isCluster = cmp.isCluster,
                databaseId = database.id
            )
        }
    }

    private fun deduplicatePoints(points: List<MapPoint>): List<MapPoint> {
        val seen = HashSet<String>(points.size)
        val result = ArrayList<MapPoint>(points.size)
        for (p in points) {
            if (seen.add("${p.bssidDecimal}:${p.databaseId}")) {
                result.add(p)
            }
        }
        return result
    }

    private fun mergePoints(existing: List<MapPoint>, newPoints: List<MapPoint>): List<MapPoint> {
        val seen = HashSet<String>(existing.size + newPoints.size)
        val result = ArrayList<MapPoint>(existing.size + newPoints.size)
        for (p in existing) {
            if (seen.add("${p.bssidDecimal}:${p.databaseId}")) {
                result.add(p)
            }
        }
        for (p in newPoints) {
            if (seen.add("${p.bssidDecimal}:${p.databaseId}")) {
                result.add(p)
            }
        }
        return result
    }

    private fun padBoundingBox(bounds: BoundingBox): BoundingBox {
        val latPadding = (bounds.latNorth - bounds.latSouth) * 0.5
        val lonPadding = (bounds.lonEast - bounds.lonWest) * 0.5
        return BoundingBox(
            bounds.latNorth + latPadding,
            bounds.lonEast + lonPadding,
            bounds.latSouth - latPadding,
            bounds.lonWest - lonPadding
        )
    }

    private fun prunePointsToBounds(points: List<MapPoint>, bounds: BoundingBox): List<MapPoint> {
        return points.filter { p ->
            p.latitude >= bounds.latSouth && p.latitude <= bounds.latNorth &&
                    p.longitude >= bounds.lonWest && p.longitude <= bounds.lonEast
        }
    }

    private suspend fun loadMapApiPoints(
        database: DbItem,
        boundingBox: BoundingBox,
        zoom: Double
    ): List<NetworkPoint> = withContext(Dispatchers.IO) {
        try {
            val mapHelper = mapHelpers.getOrPut(database.id) {
                Log.d(TAG, "Creating map helper on demand for ${database.id}")
                if (database.apiProtocol == "3wifi_app") {
                    ThreeWifiAppMapHelper(
                        getApplication(),
                        database.path,
                        database.jwtToken
                    )
                } else {
                    ThreeWifiDevMapHelper(
                        getApplication(),
                        database.path,
                        database.apiReadKey ?: "000000000000"
                    )
                }
            }

            Log.d(TAG, "Loading points via map API for ${database.id}")
            val mapPoints =
                mapHelper.getPointsInBoundingBox(boundingBox, zoom, getMaxPointsForZoom(zoom))

            Log.d(TAG, "Map API returned ${mapPoints.size} points for ${database.id}")

            mapPoints.mapIndexed { index, mapPoint ->
                if (index % 1000 == 0) {
                    yield()
                }

                if (mapPoint.count > 1) {
                    NetworkPoint(
                        latitude = mapPoint.latitude,
                        longitude = mapPoint.longitude,
                        bssidDecimal = -1L,
                        source = getApplication<Application>().getString(R.string.wm_source_server_cluster),
                        databaseId = database.id,
                        essid = getApplication<Application>().getString(
                            R.string.wm_cluster_label,
                            mapPoint.count
                        ),
                        color = getColorForDatabase(database.id),
                        clusterCount = mapPoint.count,
                        isCluster = true
                    )
                } else if (mapPoint.essid != null || mapPoint.bssid != null) {
                    val essid = mapPoint.essid
                        ?: getApplication<Application>().getString(R.string.unknown_ssid)
                    val password = mapPoint.password
                    val bssid = mapPoint.bssid

                    val record = NetworkRecord(
                        essid = essid,
                        password = password,
                        wpsPin = null,
                        routerModel = null,
                        adminCredentials = emptyList(),
                        isHidden = false,
                        isWifiDisabled = false,
                        timeAdded = null,
                        security = mapPoint.securityType,
                        lanMask = null,
                        wanMask = null,
                        wanGateway = null,
                        dns1 = null,
                        dns2 = null,
                        dns3 = null,
                        noWifiKey = null,
                        noBssid = null,
                        noWps = null,
                        ip = null,
                        lanIp = null,
                        wanIp = null,
                        iprange = null,
                        port = null,
                        time = null,
                        cmtid = null,
                        source = database.apiProtocol ?: database.type,
                        sourceRaw = null,
                        comment = null,
                        rawData = mapOf(
                            "bssid" to bssid,
                            "essid" to essid,
                            "password" to password,
                            "source" to "map_api"
                        )
                    )

                    NetworkPoint(
                        latitude = mapPoint.latitude,
                        longitude = mapPoint.longitude,
                        bssidDecimal = mapPoint.bssidDecimal,
                        source = database.apiProtocol ?: database.type,
                        databaseId = database.id,
                        essid = essid,
                        password = password,
                        color = getColorForDatabase(database.id),
                        isDataLoaded = true,
                        allRecords = listOf(record)
                    )
                } else {
                    val htmlData = parseHtmlData(mapPoint.popupHtml)
                    val essid = htmlData["essid"]
                        ?: getApplication<Application>().getString(R.string.unknown_ssid)
                    val password = htmlData["password"]
                    val bssid = htmlData["bssid"]

                    val record = NetworkRecord(
                        essid = essid,
                        password = password,
                        wpsPin = null,
                        routerModel = null,
                        adminCredentials = emptyList(),
                        isHidden = false,
                        isWifiDisabled = false,
                        timeAdded = null,
                        security = null,
                        lanMask = null,
                        wanMask = null,
                        wanGateway = null,
                        dns1 = null,
                        dns2 = null,
                        dns3 = null,
                        noWifiKey = null,
                        noBssid = null,
                        noWps = null,
                        ip = null,
                        lanIp = null,
                        wanIp = null,
                        iprange = null,
                        port = null,
                        time = null,
                        cmtid = null,
                        source = null,
                        sourceRaw = null,
                        comment = null,
                        rawData = mapOf(
                            "bssid" to bssid,
                            "essid" to essid,
                            "password" to password,
                            "source" to "map_api"
                        )
                    )

                    NetworkPoint(
                        latitude = mapPoint.latitude,
                        longitude = mapPoint.longitude,
                        bssidDecimal = mapPoint.bssidDecimal,
                        source = database.type,
                        databaseId = database.id,
                        essid = essid,
                        password = password,
                        color = getColorForDatabase(database.id),
                        isDataLoaded = true,
                        allRecords = listOf(record)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading map API points for ${database.id}", e)
            emptyList()
        }
    }

    private fun parseHtmlData(html: String?): Map<String, String?> {
        if (html.isNullOrBlank()) return emptyMap()

        try {
            val parts = html.split("<br>")
            val result = mutableMapOf<String, String?>()

            if (parts.size >= 1) {
                result["bssid"] = parts[0].trim()
            }
            if (parts.size >= 2) {
                val essid = parts[1].trim()
                result["essid"] = if (essid == "&lt;empty&gt;" || essid.isEmpty()) null else essid
            }
            if (parts.size >= 3) {
                val password = parts[2].trim()
                result["password"] =
                    if (password == "&lt;empty&gt;" || password.isEmpty()) null else password
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing HTML data: $html", e)
            return emptyMap()
        }
    }

    private suspend fun loadClusteredPointsFromDatabase(
        database: DbItem,
        boundingBox: BoundingBox,
        tileRange: TileRange?,
        tileZoom: Int,
        zoom: Double,
        scatterMode: Boolean
    ): List<ClusteredMapPoint> = withContext(Dispatchers.IO) {
        val points = when (database.dbType) {
            DbType.LOCAL_APP_DB -> {
                val dbHelper = LocalAppDbHelper(getApplication())
                val result = try {
                    if (tileRange != null) {
                        dbHelper.getClusteredPointsByTileRange(
                            tileRange.minX, tileRange.minY,
                            tileRange.maxX, tileRange.maxY,
                            tileZoom, scatterMode
                        )
                    } else {
                        val boundsToUse = boundingBox
                        val localPoints = dbHelper.getPointsInBounds(
                            boundsToUse.latSouth,
                            boundsToUse.latNorth,
                            boundsToUse.lonWest,
                            boundsToUse.lonEast,
                            getMaxPointsForZoom(zoom)
                        )

                        localPoints.mapNotNull { network ->
                            if (network.latitude == null || network.longitude == null) {
                                null
                            } else {
                                val macDecimal = try {
                                    network.macAddress.replace(":", "").replace("-", "")
                                        .toLongOrNull(16)
                                        ?: network.macAddress.toLongOrNull()
                                        ?: -1L
                                } catch (_: Exception) {
                                    -1L
                                }

                                if (macDecimal == -1L) null
                                else ClusteredMapPoint(
                                    macDecimal,
                                    network.latitude!!,
                                    network.longitude!!,
                                    1,
                                    false
                                )
                            }
                        }
                    }
                } finally {
                    dbHelper.close()
                }
                result
            }

            DbType.HANDSHAKE_STORAGE -> {
                val dbHelper = HandshakeMetadataDbHelper(getApplication())
                val result = try {
                    val boundsToUse = if (tileRange != null) {
                        QuadkeyUtils.getTileRangeBounds(tileRange, tileZoom)
                    } else {
                        boundingBox
                    }
                    dbHelper.getPointsInBounds(
                        boundsToUse.latSouth,
                        boundsToUse.latNorth,
                        boundsToUse.lonWest,
                        boundsToUse.lonEast
                    ).mapNotNull { item ->
                        if (item.latitude == null || item.longitude == null) null
                        else {
                            val bssidStr = item.bssid?.replace(":", "")?.replace("-", "")
                            val bssidDecimal = bssidStr?.toLongOrNull(16) ?: -1L
                            if (bssidDecimal == -1L) null
                            else ClusteredMapPoint(
                                bssidDecimal,
                                item.latitude,
                                item.longitude,
                                1,
                                false
                            )
                        }
                    }
                } finally {
                    dbHelper.close()
                }
                result
            }

            DbType.WIFI_API -> {
                if (database.supportsMapApi) {
                    val mapHelper = mapHelpers[database.id]
                    if (mapHelper != null) {
                        val mapPoints = mapHelper.getPointsInBoundingBox(
                            boundingBox,
                            zoom,
                            getMaxPointsForZoom(zoom)
                        )

                        mapPoints.map { mapPoint ->
                            ClusteredMapPoint(
                                mapPoint.bssidDecimal,
                                mapPoint.latitude,
                                mapPoint.longitude,
                                mapPoint.count,
                                mapPoint.count > 1
                            )
                        }
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                if (database.directPath.isNullOrEmpty()) {
                    emptyList()
                } else {
                    if (tileRange != null) {
                        externalIndexManager.getClusteredPointsByTileRange(
                            database.id,
                            database.directPath,
                            database.tableName ?: "geo",
                            database.columnMap,
                            tileRange.minX, tileRange.minY,
                            tileRange.maxX, tileRange.maxY,
                            tileZoom,
                            scatterMode
                        ) ?: emptyList()
                    } else {
                        externalIndexManager.getClusteredPointsInBoundingBox(
                            database.id,
                            database.directPath,
                            database.tableName ?: "geo",
                            database.columnMap,
                            boundingBox,
                            zoom,
                            scatterMode
                        ) ?: emptyList()
                    }
                }
            }

            else -> {
                val helper = getHelper(database)
                if (helper == null) {
                    emptyList()
                } else when (helper) {
                    is SQLite3WiFiHelper -> {
                        if (helper.corruptionDetected || helper.database == null) {
                            emptyList()
                        } else {
                            val clusteredPoints = if (tileRange != null) {
                                helper.getClusteredPointsByTileRange(
                                    tileRange.minX, tileRange.minY,
                                    tileRange.maxX, tileRange.maxY,
                                    tileZoom, scatterMode
                                )
                            } else {
                                helper.getClusteredPointsInBoundingBox(
                                    boundingBox,
                                    zoom,
                                    getMaxPointsForZoom(zoom),
                                    scatterMode
                                )
                            }

                            clusteredPoints
                        }
                    }

                    is SQLiteCustomHelper -> {
                        if (database.tableName == null || database.columnMap == null) {
                            emptyList<ClusteredMapPoint>()
                        } else {
                            helper.getPointsInBoundingBoxLegacy(
                                boundingBox,
                                database.tableName,
                                database.columnMap
                            )
                        }
                    }

                    else -> {
                        emptyList()
                    }
                }
            }
        }

        points
    }

    fun clearCache() {
        currentLoadingJob?.cancel()
        viewModelScope.launch {
            tilePointsCache.clear()
        }
        lastUpdateTime = 0
        Log.d(TAG, "Cleared all caches and cancelled background jobs")
    }

    fun clearAllHelpers() {
        databaseHelpers.values.forEach { it.close() }
        databaseHelpers.clear()
        mapHelpers.clear()
    }

    fun forceRefresh() {
        clearCache()
        viewModelScope.launch(Dispatchers.IO) {
            clearAllHelpers()
        }
    }

    fun resetState() {
        clearCache()
        viewModelScope.launch(Dispatchers.IO) {
            clearAllHelpers()
        }
    }

    fun convertBssidToString(decimal: Long): String {
        return String.format("%012X", decimal)
            .replace(HEX_PAIR_REGEX, "$1:").dropLast(1)
    }

    fun reloadAvailableDatabases() {
        viewModelScope.launch {
            try {
                dbSetupViewModel.loadDbList()
                dbSetupViewModel.dbList.value?.let { dbList ->
                    val databases = addLocalDbIfMissing(dbList)
                    _availableDatabases.postValue(databases)
                    assignColorsToDatabase(databases)
                    Log.d(
                        TAG,
                        "Successfully reloaded available databases, count: ${databases.size}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading available databases", e)
            }
        }
    }

    fun loadPointInfo(point: NetworkPoint) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(
                    TAG,
                    "Loading info for point with BSSID: ${convertBssidToString(point.bssidDecimal)}"
                )
                val database = _availableDatabases.value?.find { it.id == point.databaseId }

                if (database != null) {
                    Log.d(TAG, "Found database for point: ${database.id}, type: ${database.dbType}")

                    when (database.dbType) {
                        DbType.LOCAL_APP_DB -> {
                            Log.d(TAG, "Getting point info from local database")
                            val dbHelper = LocalAppDbHelper(getApplication())
                            val bssidStr = convertBssidToString(point.bssidDecimal)

                            val results = try {
                                dbHelper.searchRecordsWithFilters(
                                    bssidStr,
                                    filterByName = false,
                                    filterByMac = true,
                                    filterByPassword = false,
                                    filterByWps = false
                                )
                            } finally {
                                dbHelper.close()
                            }

                            if (results.isNotEmpty()) {
                                val records = results.map { network ->
                                    NetworkRecord(
                                        essid = network.wifiName,
                                        password = network.wifiPassword,
                                        wpsPin = network.wpsCode,
                                        routerModel = null,
                                        adminCredentials = parseAdminCredentials(network.adminPanel),
                                        isHidden = false,
                                        isWifiDisabled = false,
                                        timeAdded = null,
                                        security = null,
                                        lanMask = null,
                                        wanMask = null,
                                        wanGateway = null,
                                        dns1 = null,
                                        dns2 = null,
                                        dns3 = null,
                                        noWifiKey = null,
                                        noBssid = null,
                                        noWps = null,
                                        ip = null,
                                        lanIp = null,
                                        wanIp = null,
                                        iprange = null,
                                        port = null,
                                        time = null,
                                        cmtid = null,
                                        source = null,
                                        sourceRaw = null,
                                        comment = null,
                                        rawData = mapOf(
                                            "id" to network.id,
                                            "wifiName" to network.wifiName,
                                            "macAddress" to network.macAddress,
                                            "wifiPassword" to network.wifiPassword,
                                            "wpsCode" to network.wpsCode,
                                            "adminPanel" to network.adminPanel,
                                            "latitude" to network.latitude,
                                            "longitude" to network.longitude
                                        )
                                    )
                                }

                                point.allRecords = records
                                point.essid = records.firstOrNull()?.essid
                                point.password = results.firstOrNull()?.wifiPassword
                                point.wpsPin = results.firstOrNull()?.wpsCode
                                point.isDataLoaded = true
                                Log.d(TAG, "Retrieved ${records.size} records from local database")
                            } else {
                                Log.w(TAG, "No info found in local database for BSSID: $bssidStr")
                            }
                        }

                        DbType.HANDSHAKE_STORAGE -> {
                            val dbHelper = HandshakeMetadataDbHelper(getApplication())
                            try {
                                val bssidStr = convertBssidToString(point.bssidDecimal)
                                val results = dbHelper.getByBssid(bssidStr)
                                if (results.isNotEmpty()) {
                                    val item = results.first()
                                    val record = NetworkRecord(
                                        essid = item.essid,
                                        password = item.crackedPassword,
                                        wpsPin = null,
                                        routerModel = null,
                                        adminCredentials = emptyList(),
                                        isHidden = false,
                                        isWifiDisabled = false,
                                        timeAdded = null,
                                        security = null,
                                        lanMask = null,
                                        wanMask = null,
                                        wanGateway = null,
                                        dns1 = null,
                                        dns2 = null,
                                        dns3 = null,
                                        noWifiKey = null,
                                        noBssid = null,
                                        noWps = null,
                                        ip = null,
                                        lanIp = null,
                                        wanIp = null,
                                        iprange = null,
                                        port = null,
                                        time = null,
                                        cmtid = null,
                                        source = null,
                                        sourceRaw = null,
                                        comment = null,
                                        rawData = mapOf(
                                            "essid" to item.essid,
                                            "bssid" to item.bssid,
                                            "password" to item.crackedPassword,
                                            "fileName" to item.fileName
                                        )
                                    )
                                    point.allRecords = listOf(record)
                                    point.essid = item.essid
                                    point.password = item.crackedPassword
                                    point.isDataLoaded = true
                                    Log.d(TAG, "Retrieved handshake info for BSSID: $bssidStr")
                                } else {
                                    Log.w(TAG, "No handshake found for BSSID: $bssidStr")
                                }
                            } finally {
                                dbHelper.close()
                            }
                        }

                        DbType.WIFI_API -> {
                            if (database.supportsMapApi) {
                                if (point.isDataLoaded && point.allRecords.isNotEmpty()) {
                                    Log.d(
                                        TAG,
                                        "Using already loaded data for point ${
                                            convertBssidToString(point.bssidDecimal)
                                        }"
                                    )
                                } else if (point.essid != null || point.password != null) {
                                    Log.d(TAG, "Creating record from preloaded point data")
                                    val record = NetworkRecord(
                                        essid = point.essid
                                            ?: getApplication<Application>().getString(R.string.unknown_ssid),
                                        password = point.password,
                                        wpsPin = point.wpsPin,
                                        routerModel = null,
                                        adminCredentials = emptyList(),
                                        isHidden = false,
                                        isWifiDisabled = false,
                                        timeAdded = null,
                                        security = null,
                                        lanMask = null,
                                        wanMask = null,
                                        wanGateway = null,
                                        dns1 = null,
                                        dns2 = null,
                                        dns3 = null,
                                        noWifiKey = null,
                                        noBssid = null,
                                        noWps = null,
                                        ip = null,
                                        lanIp = null,
                                        wanIp = null,
                                        iprange = null,
                                        port = null,
                                        time = null,
                                        cmtid = null,
                                        source = null,
                                        sourceRaw = null,
                                        comment = null,
                                        rawData = mapOf(
                                            "bssid" to convertBssidToString(point.bssidDecimal),
                                            "essid" to point.essid,
                                            "password" to point.password,
                                            "wpsPin" to point.wpsPin
                                        )
                                    )

                                    point.allRecords = listOf(record)
                                    point.isDataLoaded = true
                                } else {
                                    val mapHelper = mapHelpers.getOrPut(database.id) {
                                        Log.d(
                                            TAG,
                                            "Creating map helper on demand for ${database.id}"
                                        )
                                        if (database.apiProtocol == "3wifi_app") {
                                            ThreeWifiAppMapHelper(
                                                getApplication(),
                                                database.path,
                                                database.jwtToken
                                            )
                                        } else {
                                            ThreeWifiDevMapHelper(
                                                getApplication(),
                                                database.path,
                                                database.apiReadKey ?: "000000000000"
                                            )
                                        }
                                    }
                                    Log.d(
                                        TAG,
                                        "Loading point info via map API for ${database.id}"
                                    )
                                    val info = mapHelper.getPointDetails(point.bssidDecimal)

                                    if (info != null) {
                                        Log.d(TAG, "Retrieved info via map API: $info")
                                        val record = NetworkRecord(
                                            essid = info["essid"] as? String
                                                ?: getApplication<Application>().getString(R.string.unknown_ssid),
                                            password = (info["key"] as? String)?.takeIf { it.isNotEmpty() && it != "0" && it != "-" },
                                            wpsPin = (info["wps"] as? String)?.takeIf { it.isNotEmpty() && it != "0" && it != "-" },
                                            routerModel = null,
                                            adminCredentials = emptyList(),
                                            isHidden = false,
                                            isWifiDisabled = false,
                                            timeAdded = info["time"] as? String,
                                            security = null,
                                            lanMask = null,
                                            wanMask = null,
                                            wanGateway = null,
                                            dns1 = null,
                                            dns2 = null,
                                            dns3 = null,
                                            noWifiKey = null,
                                            noBssid = null,
                                            noWps = null,
                                            ip = null,
                                            lanIp = null,
                                            wanIp = null,
                                            iprange = null,
                                            port = null,
                                            time = null,
                                            cmtid = null,
                                            source = info["source"] as? String,
                                            sourceRaw = null,
                                            comment = info["comment"] as? String,
                                            rawData = info
                                        )

                                        point.allRecords = listOf(record)
                                        point.essid = record.essid
                                        point.password = record.password
                                        point.wpsPin = record.wpsPin
                                        point.isDataLoaded = true
                                    } else {
                                        Log.w(TAG, "No info found via map API")
                                    }
                                }
                            } else {
                                Log.d(TAG, "Database ${database.id} doesn't support map API")
                            }
                        }

                        DbType.SQLITE_FILE_CUSTOM, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                            if (database.directPath.isNullOrEmpty()) {
                                Log.e(TAG, "Direct path is null for database ${database.id}")
                                _error.postValue(getApplication<Application>().getString(R.string.directpath_missing))
                                return@launch
                            }

                            if (database.tableName.isNullOrEmpty()) {
                                Log.e(TAG, "Table name is null for database ${database.id}")
                                _error.postValue(getApplication<Application>().getString(R.string.column_mapping_missing))
                                return@launch
                            }

                            if (database.columnMap.isNullOrEmpty()) {
                                Log.e(
                                    TAG,
                                    "Column map is null or empty for database ${database.id}"
                                )
                                _error.postValue(getApplication<Application>().getString(R.string.column_mapping_missing))
                                return@launch
                            }

                            Log.d(TAG, "Getting point info from external index manager")
                            val infoList = externalIndexManager.getPointInfo(
                                database.directPath,
                                database.tableName,
                                database.columnMap,
                                point.bssidDecimal
                            )

                            if (infoList != null && infoList.isNotEmpty()) {
                                Log.d(TAG, "Retrieved ${infoList.size} records for point")

                                val records = infoList.map { info ->
                                    val essid = database.columnMap?.get("essid")?.let { colName ->
                                        info[colName]?.toString()
                                    }
                                    val password =
                                        database.columnMap?.get("wifi_pass")?.let { colName ->
                                            info[colName]?.toString()
                                        }
                                    val wpsPin =
                                        database.columnMap?.get("wps_pin")?.let { colName ->
                                            info[colName]?.toString()
                                        }
                                    val routerModel = info["name"]?.toString()
                                    val authData = resolveAdminAuthData(database.columnMap, info)
                                    val hiddenData = info["Hidden"]?.toString()
                                    val radioOffData = info["RadioOff"]?.toString()
                                    val timeData =
                                        info["time"]?.toString() ?: info["timestamp"]?.toString()

                                    NetworkRecord(
                                        essid = essid
                                            ?: getApplication<Application>().getString(R.string.unknown_ssid),
                                        password = password,
                                        wpsPin = wpsPin,
                                        routerModel = routerModel,
                                        adminCredentials = parseAdminCredentials(authData),
                                        isHidden = parseHiddenStatus(hiddenData),
                                        isWifiDisabled = parseWifiDisabledStatus(radioOffData),
                                        timeAdded = timeData,
                                        security = info["Security"] as? String,
                                        lanMask = info["LANMask"] as? String,
                                        wanMask = info["WANMask"] as? String,
                                        wanGateway = info["WANGateway"] as? String,
                                        dns1 = info["DNS1"] as? String,
                                        dns2 = info["DNS2"] as? String,
                                        dns3 = info["DNS3"] as? String,
                                        noWifiKey = info["NoWiFiKey"] as? Int,
                                        noBssid = info["NoBSSID"] as? Int,
                                        noWps = info["NoWPS"] as? Int,
                                        ip = info["ip"] as? String,
                                        lanIp = info["LANIP"] as? String,
                                        wanIp = info["WANIP"] as? String,
                                        iprange = info["iprange"] as? Int,
                                        port = info["port"] as? Int,
                                        time = info["time"] as? Long,
                                        cmtid = info["cmtid"] as? Int,
                                        source = info["source"]?.let { getSourceLabel(it as Int) },
                                        sourceRaw = info["source"] as? Int,
                                        comment = info["comment"] as? String,
                                        rawData = info
                                    )
                                }

                                point.allRecords = records
                                point.essid = records.firstOrNull()?.essid
                                point.password = records.firstOrNull()?.password
                                point.wpsPin = records.firstOrNull()?.wpsPin
                                point.routerModel = records.firstOrNull()?.routerModel
                                point.isHidden = records.any { it.isHidden }
                                point.isWifiDisabled = records.any { it.isWifiDisabled }
                                point.isDataLoaded = true
                            } else {
                                Log.w(TAG, "No info found for point")
                            }
                        }

                        else -> {
                            val helper = getHelper(database)
                            if (helper == null) {
                                Log.w(
                                    TAG,
                                    "Helper is null for ${database.id}, cannot get point info"
                                )
                                return@launch
                            }

                            Log.d(
                                TAG,
                                "Using helper ${helper.javaClass.simpleName} for getting point info"
                            )

                            when (helper) {
                                is SQLite3WiFiHelper -> {
                                    val allInfo = helper.loadAllNetworkInfo(point.bssidDecimal)

                                    if (allInfo.isNotEmpty()) {
                                        Log.d(
                                            TAG,
                                            "Retrieved ${allInfo.size} records from SQLite3WiFiHelper"
                                        )

                                        val records = allInfo.map { info ->
                                            Log.d(TAG, "Record info: $info")
                                            val geoSource = info["source"] as? Int
                                            val commentText = info["comment"] as? String
                                            NetworkRecord(
                                                essid = info["ESSID"] as? String,
                                                password = info["WiFiKey"] as? String,
                                                wpsPin = info["WPSPIN"]?.toString(),
                                                routerModel = info["name"] as? String,
                                                adminCredentials = parseAdminCredentials(info["Authorization"] as? String),
                                                isHidden = info["Hidden"]?.toString() == "b1",
                                                isWifiDisabled = info["RadioOff"]?.toString() == "b1",
                                                timeAdded = info["time"] as? String,
                                                security = info["Security"] as? String,
                                                lanMask = info["LANMask"] as? String,
                                                wanMask = info["WANMask"] as? String,
                                                wanGateway = info["WANGateway"] as? String,
                                                dns1 = info["DNS1"] as? String,
                                                dns2 = info["DNS2"] as? String,
                                                dns3 = info["DNS3"] as? String,
                                                noWifiKey = info["NoWiFiKey"] as? Int,
                                                noBssid = info["NoBSSID"] as? Int,
                                                noWps = info["NoWPS"] as? Int,
                                                ip = info["ip"] as? String,
                                                lanIp = info["LANIP"] as? String,
                                                wanIp = info["WANIP"] as? String,
                                                iprange = info["iprange"] as? Int,
                                                port = info["port"] as? Int,
                                                time = info["time"] as? Long,
                                                cmtid = info["cmtid"] as? Int,
                                                source = getSourceLabel(geoSource),
                                                sourceRaw = geoSource,
                                                comment = commentText,
                                                rawData = info,
                                                ipRaw = info["ip"] as? Long,
                                                lanIpRaw = info["LANIP"] as? Long,
                                                wanIpRaw = info["WANIP"] as? Long,
                                                lanMaskRaw = info["LANMask"] as? Long,
                                                wanMaskRaw = info["WANMask"] as? Long,
                                                wanGatewayRaw = info["WANGateway"] as? Long,
                                                dns1Raw = info["DNS1"] as? Long,
                                                dns2Raw = info["DNS2"] as? Long,
                                                dns3Raw = info["DNS3"] as? Long
                                            )
                                        }

                                        point.allRecords = records
                                        point.essid = records.firstOrNull()?.essid
                                        point.password = records.firstOrNull()?.password
                                        point.wpsPin = records.firstOrNull()?.wpsPin
                                        point.routerModel = records.firstOrNull()?.routerModel
                                        point.isHidden = records.any { it.isHidden }
                                        point.isWifiDisabled = records.any { it.isWifiDisabled }
                                        point.isDataLoaded = true
                                    } else {
                                        Log.w(TAG, "No info found from SQLite3WiFiHelper")
                                    }
                                }

                                is SQLiteCustomHelper -> {
                                    if (database.tableName != null && database.columnMap != null) {
                                        Log.d(
                                            TAG,
                                            "Searching network by BSSID in SQLiteCustomHelper"
                                        )
                                        val bssidStr = convertBssidToString(point.bssidDecimal)

                                        val infoList = helper.searchNetworksByBSSIDAndFields(
                                            database.tableName,
                                            database.columnMap,
                                            bssidStr,
                                            setOf("mac"),
                                            SearchMode.EXACT
                                        )

                                        if (infoList.isNotEmpty()) {
                                            Log.d(
                                                TAG,
                                                "Retrieved ${infoList.size} records from SQLiteCustomHelper"
                                            )

                                            val records = infoList.map { info ->
                                                val essid =
                                                    database.columnMap["essid"]?.let { info[it]?.toString() }
                                                val password =
                                                    database.columnMap["wifi_pass"]?.let { info[it]?.toString() }
                                                val wpsPin =
                                                    database.columnMap["wps_pin"]?.let { info[it]?.toString() }
                                                val routerModel = info["name"]?.toString()
                                                val authData = resolveAdminAuthData(
                                                    database.columnMap,
                                                    info
                                                )
                                                val hiddenData = info["Hidden"]?.toString()
                                                val radioOffData = info["RadioOff"]?.toString()
                                                val timeData = info["time"]?.toString()

                                                NetworkRecord(
                                                    essid = essid,
                                                    password = password,
                                                    wpsPin = wpsPin,
                                                    routerModel = routerModel,
                                                    adminCredentials = parseAdminCredentials(
                                                        authData
                                                    ),
                                                    isHidden = hiddenData == "b1",
                                                    isWifiDisabled = radioOffData == "b1",
                                                    timeAdded = timeData,
                                                    security = info["Security"]?.toString(),
                                                    lanMask = info["LANMask"]?.toString(),
                                                    wanMask = info["WANMask"]?.toString(),
                                                    wanGateway = info["WANGateway"]?.toString(),
                                                    dns1 = info["DNS1"]?.toString(),
                                                    dns2 = info["DNS2"]?.toString(),
                                                    dns3 = info["DNS3"]?.toString(),
                                                    noWifiKey = info["NoWiFiKey"] as? Int,
                                                    noBssid = info["NoBSSID"] as? Int,
                                                    noWps = info["NoWPS"] as? Int,
                                                    ip = info["ip"]?.toString(),
                                                    lanIp = info["LANIP"]?.toString(),
                                                    wanIp = info["WANIP"]?.toString(),
                                                    iprange = info["iprange"] as? Int,
                                                    port = info["port"] as? Int,
                                                    time = info["time"] as? Long,
                                                    cmtid = info["cmtid"] as? Int,
                                                    source = info["source"]?.let { getSourceLabel(it as Int) },
                                                    sourceRaw = info["source"] as? Int,
                                                    comment = info["comment"] as? String,
                                                    rawData = info
                                                )
                                            }

                                            point.allRecords = records
                                            point.essid = records.firstOrNull()?.essid
                                            point.password = records.firstOrNull()?.password
                                            point.wpsPin = records.firstOrNull()?.wpsPin
                                            point.routerModel = records.firstOrNull()?.routerModel
                                            point.isHidden = records.any { it.isHidden }
                                            point.isWifiDisabled = records.any { it.isWifiDisabled }
                                            point.isDataLoaded = true
                                        } else {
                                            Log.w(
                                                TAG,
                                                "No info found from SQLiteCustomHelper for BSSID: $bssidStr"
                                            )
                                        }
                                    } else {
                                        Log.e(
                                            TAG,
                                            "Table name or column map is null for database ${database.id}"
                                        )
                                    }
                                }

                                else -> {
                                    Log.e(TAG, "Unknown helper type: ${helper.javaClass.name}")
                                }
                            }
                        }
                    }

                    Log.d(
                        TAG,
                        "Posting selected point with ESSID: ${point.essid}, isDataLoaded: ${point.isDataLoaded}"
                    )
                    _selectedPoint.postValue(point)
                } else {
                    Log.e(
                        TAG,
                        "Database not found for point with BSSID: ${convertBssidToString(point.bssidDecimal)}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading point info", e)
                _error.postValue(getApplication<Application>().getString(R.string.point_loading_error))
            }
        }
    }

    suspend fun loadPointInfoByBssid(
        bssidDecimal: Long,
        databaseId: String,
        latitude: Double = 0.0,
        longitude: Double = 0.0
    ) {
        val point = NetworkPoint(
            latitude = latitude,
            longitude = longitude,
            bssidDecimal = bssidDecimal,
            source = "",
            databaseId = databaseId
        )
        loadPointInfo(point)
    }

    private fun parseHiddenStatus(value: String?): Boolean {
        return when (value?.lowercase(java.util.Locale.ROOT)?.trim()) {
            "b1", "1", "true", "yes" -> true
            else -> false
        }
    }

    private fun parseWifiDisabledStatus(value: String?): Boolean {
        return when (value?.lowercase(java.util.Locale.ROOT)?.trim()) {
            "b1", "1", "true", "yes" -> true
            else -> false
        }
    }

    private val _ipRangesLoading = MutableLiveData<Boolean>()
    val ipRangesLoading: LiveData<Boolean> = _ipRangesLoading

    private val _visiblePointCounts = mutableMapOf<String, Int>()
    private val _totalPointCounts = mutableMapOf<String, Int>()

    fun getTotalPointCount(databaseId: String): Int = _totalPointCounts[databaseId] ?: 0

    fun updatePointCounts(points: List<MapPoint>) {
        _visiblePointCounts.clear()
        _totalPointCounts.clear()

        for (point in points) {
            val dbId = point.databaseId
            _totalPointCounts[dbId] = _totalPointCounts.getOrDefault(dbId, 0) + point.clusterCount
            _visiblePointCounts[dbId] = _visiblePointCounts.getOrDefault(dbId, 0) + 1
        }
    }

    fun searchIpRangesByCenter(
        lat: Double,
        lon: Double,
        radius: Double,
        rdapEnrichment: Boolean = false,
        countPoints: Boolean = false
    ) {
        ipRangeSearchJob?.cancel()
        _ipRangesLoading.value = true
        ipRangeSearchJob = viewModelScope.launch(Dispatchers.IO) {
            try {

                val selectedDbs = _availableDatabases.value
                    ?.filter { it.id in _selectedDatabaseIds }
                    ?: emptyList()

                Log.d(
                    "WiFiMapVM",
                    "searchIpRanges: ${selectedDbs.size} selected DBs, types=${selectedDbs.map { it.dbType }}, rdapEnrichment=$rdapEnrichment, countPoints=$countPoints"
                )

                val allRanges = ArrayList<IpRangeResult>()
                val rangesById = HashMap<String, IpRangeResult>()

                for (db in selectedDbs) {
                    if (db.dbType == DbType.SQLITE_FILE_P3WIFI || db.dbType == DbType.SMARTLINK_SQLITE_FILE_P3WIFI) {
                        val helper = getHelper(db)
                        if (helper is SQLite3WiFiHelper) {
                            val dbColor = getColorForDatabase(db.id)
                            val ranges = helper.getIpRanges(lat, lon, radius, true, countPoints)

                            for (map in ranges) {
                                val rangeStr = map["range"] as? String ?: ""
                                if (rangeStr.isEmpty()) continue

                                val existing = rangesById[rangeStr]
                                val mapCount = (map["count"] as? Int) ?: 0
                                if (existing != null) {
                                    if (countPoints) {
                                        rangesById[rangeStr] = existing.copy(
                                            pointCount = existing.pointCount + mapCount
                                        )
                                    }
                                    continue
                                }

                                val result = IpRangeResult(
                                    range = rangeStr,
                                    netname = if (rdapEnrichment) (map["netname"] as? String
                                        ?: "") else "",
                                    description = if (rdapEnrichment) (map["descr"] as? String
                                        ?: "") else "",
                                    country = if (rdapEnrichment) (map["country"] as? String
                                        ?: "") else "",
                                    sourceName = db.type,
                                    databaseId = db.id,
                                    databaseColor = dbColor,
                                    pointCount = if (countPoints) mapCount else 0
                                )
                                rangesById[rangeStr] = result
                                allRanges.add(result)
                            }
                        }
                    }
                }

                _ipRanges.postValue(allRanges)
            } catch (e: Exception) {
                Log.e(TAG, "Error searching IP ranges", e)
            } finally {
                _ipRangesLoading.postValue(false)
            }
        }
    }

    private fun parseAdminCredentials(authString: String?): List<AdminCredential> {
        if (authString.isNullOrBlank()) return emptyList()

        return authString.split(" ").mapNotNull { credential ->
            val parts = credential.split(":")
            if (parts.size == 2) {
                AdminCredential(parts[0], parts[1])
            } else {
                null
            }
        }
    }

    private fun resolveAdminAuthData(
        columnMap: Map<String, String>?,
        info: Map<String, Any?>
    ): String? {
        if (columnMap != null) {
            val loginCol = columnMap["admin_login"]
            val passCol = columnMap["admin_pass"]
            if (loginCol != null && passCol != null) {
                val login = info[loginCol]?.toString().orEmpty()
                val pass = info[passCol]?.toString().orEmpty()
                if (login.isNotBlank() || pass.isNotBlank()) {
                    return "$login:$pass"
                }
            }
            val combined = columnMap["admin_panel"]?.let { info[it]?.toString() }
            if (!combined.isNullOrBlank()) return combined
        }
        return info["Authorization"]?.toString() ?: info["admin_panel"]?.toString()
    }

    private fun getSourceLabel(sourceValue: Int?): String? {
        if (sourceValue == null) return null
        return when (sourceValue) {
            0 -> getApplication<Application>().getString(R.string.source_3wifi)
            1 -> getApplication<Application>().getString(R.string.source_3wifi_dead)
            2 -> getApplication<Application>().getString(R.string.source_google)
            3 -> getApplication<Application>().getString(R.string.source_yandex)
            4 -> getApplication<Application>().getString(R.string.source_apple)
            5 -> getApplication<Application>().getString(R.string.source_apple)
            6 -> getApplication<Application>().getString(R.string.source_microsoft)
            8 -> getApplication<Application>().getString(R.string.source_skyhook)
            else -> getApplication<Application>().getString(R.string.source_unknown, sourceValue)
        }
    }

    private fun getHandshakeStorageItem(): DbItem {
        val existing = _availableDatabases.value?.find { it.dbType == DbType.HANDSHAKE_STORAGE }
        return DbItem(
            id = "handshake_storage",
            path = "handshake_storage",
            type = getApplication<Application>().getString(R.string.handshake_storage),
            dbType = DbType.HANDSHAKE_STORAGE,
            directPath = null,
            originalSizeInMB = existing?.originalSizeInMB ?: 0f,
            cachedSizeInMB = existing?.cachedSizeInMB ?: 0f,
            isMain = existing?.isMain == true,
            apiKey = null,
            tableName = null,
            columnMap = null
        )
    }

    private fun getLocalDbItem(): DbItem {
        val localDbId = "local_db"
        val existingLocalDb = _availableDatabases.value?.find { it.dbType == DbType.LOCAL_APP_DB }

        return DbItem(
            id = localDbId,
            path = "local_db",
            directPath = getApplication<Application>().getDatabasePath(LocalAppDbHelper.DATABASE_NAME).absolutePath,
            type = getApplication<Application>().getString(R.string.local_database),
            dbType = DbType.LOCAL_APP_DB,
            originalSizeInMB = existingLocalDb?.originalSizeInMB ?: 0f,
            cachedSizeInMB = existingLocalDb?.cachedSizeInMB ?: 0f,
            isMain = existingLocalDb?.isMain == true,
            apiKey = null,
            tableName = LocalAppDbHelper.TABLE_NAME,
            columnMap = mapOf(
                "mac" to LocalAppDbHelper.COLUMN_MAC_ADDRESS,
                "essid" to LocalAppDbHelper.COLUMN_WIFI_NAME,
                "wifi_pass" to LocalAppDbHelper.COLUMN_WIFI_PASSWORD,
                "wps_pin" to LocalAppDbHelper.COLUMN_WPS_CODE,
                "admin_panel" to LocalAppDbHelper.COLUMN_ADMIN_PANEL,
                "latitude" to LocalAppDbHelper.COLUMN_LATITUDE,
                "longitude" to LocalAppDbHelper.COLUMN_LONGITUDE
            )
        )
    }

    override fun onCleared() {
        dbSetupViewModel.dbList.removeObserver(dbListObserver)
        super.onCleared()
        currentLoadingJob?.cancel()
        databaseHelpers.values.forEach { it.close() }
        databaseHelpers.clear()
        mapHelpers.clear()
        externalIndexManager.close()
    }
}
