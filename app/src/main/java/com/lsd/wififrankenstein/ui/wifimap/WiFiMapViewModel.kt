package com.lsd.wififrankenstein.ui.wifimap

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.util.DatabaseIndices
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import java.util.Collections

class WiFiMapViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "WiFiMapViewModel"
    private val dbSetupViewModel = DbSetupViewModel(application)
    private val settingsPrefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _points = MutableLiveData<List<NetworkPoint>>()
    val points: LiveData<List<NetworkPoint>> = _points

    private val _selectedPoint = MutableLiveData<NetworkPoint>()
    val selectedPoint: LiveData<NetworkPoint> = _selectedPoint

    private val _availableDatabases = MutableLiveData<List<DbItem>>()
    val availableDatabases: LiveData<List<DbItem>> = _availableDatabases

    private val _loadingProgress = MutableLiveData<Int>()
    val loadingProgress: LiveData<Int> = _loadingProgress

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val databaseHelpers = mutableMapOf<String, SQLiteOpenHelper>()

    private var clusterManager = getClusterManager()

    private val pointsCache = mutableMapOf<String, List<NetworkPoint>>()

    private val _showIndexingDialog = MutableLiveData<DbItem?>()
    val showIndexingDialog: LiveData<DbItem?> = _showIndexingDialog

    private val _indexingProgress = MutableLiveData<Int>()
    val indexingProgress: LiveData<Int> = _indexingProgress

    private val externalIndexManager = ExternalIndexManager(getApplication())

    private val _addReadOnlyDb = MutableLiveData<DbItem>()
    val addReadOnlyDb: LiveData<DbItem> = _addReadOnlyDb

    private val localDbIndexManager = LocalDbIndexManager(getApplication())

    private var lastUpdateTime = 0L
    private val MIN_UPDATE_INTERVAL = 100L

    private var currentLoadingJob: Job? = null
    private val maxPointsPerZoom = mapOf(
        18.0 to 50000,
        17.0 to 40000,
        16.0 to 30000,
        15.0 to 20000,
        14.0 to 15000,
        13.0 to 10000,
        12.0 to 8000,
        11.0 to 6000,
        10.0 to 4000
    )

    fun getMinZoomForMarkers(): Double {
        val maxRestriction = 12.0
        val minRestriction = 1.0
        val zoomSetting = settingsPrefs.getFloat("map_marker_visibility_zoom", 13f)
        val maxSetting = 18f
        val ratio = zoomSetting / maxSetting
        return minRestriction + ratio * (maxRestriction - minRestriction)
    }

    init {
        viewModelScope.launch {
            dbSetupViewModel.dbList.observeForever { dbList ->
                _availableDatabases.value = dbList ?: emptyList()
            }
        }
    }

    private fun getHelper(database: DbItem): SQLiteOpenHelper {
        Log.d(TAG, "Getting helper for database: ${database.id}")
        return databaseHelpers.getOrPut(database.id) {
            Log.d(TAG, "Creating new helper for database: ${database.id}")
            when (database.dbType) {
                DbType.SQLITE_FILE_CUSTOM -> {
                    Log.d(TAG, "Creating SQLiteCustomHelper for database: ${database.id}")
                    SQLiteCustomHelper(getApplication(), database.path.toUri(), database.directPath)
                }
                else -> {
                    Log.d(TAG, "Creating SQLite3WiFiHelper for database: ${database.id}")
                    SQLite3WiFiHelper(getApplication(), database.path.toUri(), database.directPath)
                }
            }
        }
    }

    fun getPreventClusterMerge(): Boolean {
        return settingsPrefs.getBoolean("map_prevent_cluster_merge", false)
    }

    fun setPreventClusterMerge(value: Boolean) {
        settingsPrefs.edit { putBoolean("map_prevent_cluster_merge", value) }
        clusterManager = getClusterManager()
    }

    private fun getClusterManager(): MarkerClusterManager {
        val maxClusterSize = settingsPrefs.getInt("map_max_cluster_size", 1000)
        val clusterAggressiveness = settingsPrefs.getFloat("map_cluster_aggressiveness", 1.0f)
        val preventMerge = settingsPrefs.getBoolean("map_prevent_cluster_merge", false)
        val forceSeparation = settingsPrefs.getBoolean("map_force_point_separation", true)
        return MarkerClusterManager(maxClusterSize, clusterAggressiveness, preventMerge, forceSeparation)
    }

    private fun createCacheKey(boundingBox: BoundingBox): String {
        val latSouth = (boundingBox.latSouth * 100).toInt() / 100.0
        val latNorth = (boundingBox.latNorth * 100).toInt() / 100.0
        val lonWest = (boundingBox.lonWest * 100).toInt() / 100.0
        val lonEast = (boundingBox.lonEast * 100).toInt() / 100.0
        return "$latSouth,$latNorth,$lonWest,$lonEast"
    }

    private fun boundingBoxesIntersect(box1: List<Double>, box2: List<Double>): Boolean {
        return !(box1[0] > box2[1] || // latSouth1 > latNorth2
                box1[1] < box2[0] || // latNorth1 < latSouth2
                box1[2] > box2[3] || // lonWest1 > lonEast2
                box1[3] < box2[2])   // lonEast1 < lonWest2
    }

    fun handleCustomDbSelection(dbItem: DbItem, isSelected: Boolean, selectedDatabases: Set<DbItem>) {
        Log.d(TAG, "Handle DB selection: ${dbItem.id}, isSelected: $isSelected")

        if (isSelected) {
            if (dbItem.dbType == DbType.LOCAL_APP_DB &&
                selectedDatabases.any { db -> db.dbType == DbType.LOCAL_APP_DB }) {
                Log.d(TAG, "Local database already selected, skipping")
                return
            }

            when (dbItem.dbType) {
                DbType.SQLITE_FILE_CUSTOM -> {
                    try {
                        if (dbItem.tableName.isNullOrEmpty() || dbItem.columnMap.isNullOrEmpty() || dbItem.directPath.isNullOrEmpty()) {
                            Log.e(TAG, "Missing required fields in DbItem")
                            _error.postValue(getApplication<Application>().getString(R.string.column_mapping_missing))
                            return
                        }

                        if (externalIndexManager.needsIndexing(dbItem.id, dbItem.directPath)) {
                            Log.d(TAG, "Database needs external indexing, showing dialog")
                            _showIndexingDialog.value = dbItem
                        } else {
                            Log.d(TAG, "External indexes exist, adding database")
                            _addReadOnlyDb.postValue(dbItem)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking database for external indexing", e)
                        _error.postValue(getApplication<Application>().getString(R.string.db_check_error))
                    }
                }
                DbType.LOCAL_APP_DB -> {
                    try {
                        val dbHelper = LocalAppDbHelper(getApplication())
                        val db = dbHelper.readableDatabase

                        if (localDbIndexManager.needsIndexing(db)) {
                            Log.d(TAG, "Local database needs indexing, showing dialog")
                            _showIndexingDialog.value = dbItem
                        } else {
                            Log.d(TAG, "Local database indexes exist, adding database")
                            _addReadOnlyDb.postValue(dbItem)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking local database for indexing", e)
                        _error.postValue(getApplication<Application>().getString(R.string.db_check_error))
                    }
                }
                else -> {
                    _addReadOnlyDb.postValue(dbItem)
                }
            }
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
                _error.postValue(getApplication<Application>().getString(R.string.indexing_failed))
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
        databaseColors: Map<String, Int>
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < MIN_UPDATE_INTERVAL) {
            return
        }
        lastUpdateTime = currentTime

        val minZoom = getMinZoomForMarkers()
        if (zoom < minZoom) {
            Log.d(TAG, "Zoom level too low: $zoom < $minZoom")
            _points.value = emptyList()
            return
        }

        currentLoadingJob?.cancel()

        currentLoadingJob = viewModelScope.launch {
            try {
                _loadingProgress.postValue(0)

                val allPoints = withContext(Dispatchers.IO) {
                    val points = Collections.synchronizedList(mutableListOf<NetworkPoint>())
                    val totalDatabases = selectedDatabases.size

                    selectedDatabases.mapIndexed { index, database ->
                        async {
                            if (!isActive) return@async

                            try {
                                Log.d(TAG, "Loading database: ${database.id}, type: ${database.dbType}")
                                val dbPoints = loadPointsFromDatabase(database, boundingBox)

                                val networkPoints = dbPoints.map { (bssidDecimal, lat, lon) ->
                                    NetworkPoint(
                                        latitude = lat,
                                        longitude = lon,
                                        bssidDecimal = bssidDecimal,
                                        source = database.type,
                                        databaseId = database.id,
                                        color = databaseColors[database.id] ?: Color.GRAY
                                    )
                                }

                                points.addAll(networkPoints)

                                val progress = ((index + 1) * 40) / totalDatabases
                                _loadingProgress.postValue(progress)

                                Log.d(TAG, "Loaded ${networkPoints.size} points from ${database.id}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error loading points from database ${database.id}", e)
                            }
                        }
                    }.awaitAll()

                    points.toList()
                }

                if (!isActive) return@launch

                Log.d(TAG, "Total points loaded: ${allPoints.size}")
                _loadingProgress.postValue(50)

                if (allPoints.isNotEmpty()) {
                    val clusteredPoints = withContext(Dispatchers.Default) {
                        if (!isActive) return@withContext emptyList()

                        val mapCenter = Pair(
                            (boundingBox.latNorth + boundingBox.latSouth) / 2,
                            (boundingBox.lonEast + boundingBox.lonWest) / 2
                        )

                        val clusters = getClusterManager().createAdaptiveClusters(allPoints, zoom, mapCenter)
                        Log.d(TAG, "Created ${clusters.size} adaptive clusters from ${allPoints.size} points")

                        clusters.map { clusterItem ->
                            if (clusterItem.size == 1) {
                                clusterItem.points.first()
                            } else {
                                val dominantDatabaseId = clusterItem.points
                                    .groupBy { it.databaseId }
                                    .maxByOrNull { it.value.size }
                                    ?.key ?: clusterItem.points.first().databaseId

                                NetworkPoint(
                                    latitude = clusterItem.centerLatitude,
                                    longitude = clusterItem.centerLongitude,
                                    bssidDecimal = -1L,
                                    source = "Cluster",
                                    databaseId = dominantDatabaseId,
                                    essid = "Cluster (${clusterItem.size} points)",
                                    color = databaseColors[dominantDatabaseId] ?: Color.GRAY
                                )
                            }
                        }
                    }

                    if (!isActive) return@launch

                    _loadingProgress.postValue(90)
                    _points.postValue(clusteredPoints)
                } else {
                    _points.postValue(emptyList())
                }

                _loadingProgress.postValue(100)

            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Error loading points", e)
                    _error.postValue(getApplication<Application>().getString(
                        R.string.database_error,
                        e.localizedMessage ?: "Unknown error"
                    ))
                }
            }
        }
    }

    private suspend fun loadPointsFromDatabase(
        database: DbItem,
        boundingBox: BoundingBox
    ): List<Triple<Long, Double, Double>> = withContext(Dispatchers.IO) {
        val cacheKey = "${database.id}_${createCacheKey(boundingBox)}"

        pointsCache[cacheKey]?.let { cachedPoints ->
            Log.d(TAG, "Using ${cachedPoints.size} cached points for database ${database.id}")
            return@withContext cachedPoints.map {
                Triple(it.bssidDecimal, it.latitude, it.longitude)
            }
        }

        val points = when (database.dbType) {
            DbType.LOCAL_APP_DB -> {
                Log.d(TAG, "Getting points from local database")
                val dbHelper = LocalAppDbHelper(getApplication())
                val localPoints = dbHelper.getPointsInBounds(
                    boundingBox.latSouth,
                    boundingBox.latNorth,
                    boundingBox.lonWest,
                    boundingBox.lonEast
                )

                localPoints.map { network ->
                    val macDecimal = try {
                        network.macAddress.replace(":", "").replace("-", "").toLongOrNull(16)
                            ?: network.macAddress.toLongOrNull()
                            ?: -1L
                    } catch (_: Exception) {
                        -1L
                    }

                    Triple(macDecimal, network.latitude ?: 0.0, network.longitude ?: 0.0)
                }.filter { it.first != -1L }
            }
            DbType.SQLITE_FILE_CUSTOM -> {
                if (database.directPath.isNullOrEmpty()) {
                    Log.e(TAG, "Direct path is null for database ${database.id}")
                    emptyList()
                } else {
                    Log.d(TAG, "Using external indexes for database: ${database.id}")
                    externalIndexManager.getPointsInBoundingBox(database.id, boundingBox)
                }
            }
            else -> {
                val helper = getHelper(database)
                when (helper) {
                    is SQLite3WiFiHelper -> {
                        helper.getPointsInBoundingBox(boundingBox)
                    }
                    is SQLiteCustomHelper -> {
                        if (database.tableName == null || database.columnMap == null) {
                            Log.e(TAG, "Table name or column map is null for database ${database.id}")
                            emptyList()
                        } else {
                            helper.getPointsInBoundingBox(
                                boundingBox,
                                database.tableName,
                                database.columnMap
                            ) ?: emptyList()
                        }
                    }
                    else -> {
                        Log.e(TAG, "Unknown helper type: ${helper.javaClass.name}")
                        emptyList()
                    }
                }
            }
        }

        if (points.isNotEmpty()) {
            val networkPoints = points.map { (bssid, lat, lon) ->
                NetworkPoint(
                    latitude = lat,
                    longitude = lon,
                    bssidDecimal = bssid,
                    source = database.type,
                    databaseId = database.id
                )
            }
            pointsCache[cacheKey] = networkPoints
        }

        points
    }

    suspend fun createLocalDbIndexes() {
        try {
            val dbHelper = LocalAppDbHelper(getApplication())
            val db = dbHelper.writableDatabase

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
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating indexes for local database", e)
            _error.postValue(getApplication<Application>().getString(R.string.indexing_failed) + ": " + e.message)
        }
    }

    private fun getLocalDbItem(): DbItem {
        val existingLocalDb = _availableDatabases.value?.find { it.dbType == DbType.LOCAL_APP_DB }

        val localDbId = existingLocalDb?.id ?: "local_db"

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

    fun clearCache() {
        pointsCache.clear()
    }

    fun resetState() {
        clearCache()
        databaseHelpers.values.forEach { it.close() }
        databaseHelpers.clear()
    }

    fun convertBssidToString(decimal: Long): String {
        return String.format("%012X", decimal)
            .replace("(.{2})".toRegex(), "$1:").dropLast(1)
    }

    fun reloadAvailableDatabases() {
        viewModelScope.launch {
            try {
                dbSetupViewModel.loadDbList()
                dbSetupViewModel.dbList.value?.let { dbList ->
                    _availableDatabases.postValue(dbList)
                    Log.d(TAG, "Successfully reloaded available databases, count: ${dbList.size}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading available databases", e)
            }
        }
    }

    suspend fun loadPointInfo(point: NetworkPoint) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading info for point with BSSID: ${convertBssidToString(point.bssidDecimal)}")
                val database = _availableDatabases.value?.find { it.id == point.databaseId }

                if (database != null) {
                    Log.d(TAG, "Found database for point: ${database.id}, type: ${database.dbType}")

                    when (database.dbType) {
                        DbType.LOCAL_APP_DB -> {
                            Log.d(TAG, "Getting point info from local database")
                            val dbHelper = LocalAppDbHelper(getApplication())
                            val bssidStr = convertBssidToString(point.bssidDecimal)

                            val results = dbHelper.searchRecordsWithFilters(
                                bssidStr,
                                filterByName = false,
                                filterByMac = true,
                                filterByPassword = false,
                                filterByWps = false
                            )

                            if (results.isNotEmpty()) {
                                val network = results.first()
                                point.essid = network.wifiName
                                point.password = network.wifiPassword
                                point.wpsPin = network.wpsCode
                                point.isDataLoaded = true
                                Log.d(TAG, "Retrieved info from local database: ESSID=${network.wifiName}")
                            } else {
                                Log.w(TAG, "No info found in local database for BSSID: $bssidStr")
                            }
                        }
                        DbType.SQLITE_FILE_CUSTOM -> {
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
                                Log.e(TAG, "Column map is null for database ${database.id}")
                                _error.postValue(getApplication<Application>().getString(R.string.column_mapping_missing))
                                return@launch
                            }

                            Log.d(TAG, "Getting point info from external index manager")
                            val info = externalIndexManager.getPointInfo(
                                database.directPath,
                                database.tableName,
                                database.columnMap,
                                point.bssidDecimal
                            )

                            if (info != null) {
                                Log.d(TAG, "Retrieved info for point: $info")

                                database.columnMap["essid"]?.let { essidCol ->
                                    point.essid = info[essidCol]?.toString()
                                    Log.d(TAG, "Set ESSID from column $essidCol: ${point.essid}")
                                }

                                database.columnMap["wifi_pass"]?.let { passCol ->
                                    point.password = info[passCol]?.toString()
                                    Log.d(TAG, "Set password from column $passCol: ${point.password}")
                                }

                                database.columnMap["wps_pin"]?.let { wpsCol ->
                                    point.wpsPin = info[wpsCol]?.toString()
                                    Log.d(TAG, "Set WPS PIN from column $wpsCol: ${point.wpsPin}")
                                }

                                point.isDataLoaded = true
                            } else {
                                Log.w(TAG, "No info found for point")
                            }
                        }
                        else -> {
                            val helper = getHelper(database)

                            Log.d(TAG, "Using helper ${helper.javaClass.simpleName} for getting point info")

                            when (helper) {
                                is SQLite3WiFiHelper -> {
                                    val info = helper.loadNetworkInfo(point.bssidDecimal)

                                    if (info != null) {
                                        Log.d(TAG, "Retrieved info from SQLite3WiFiHelper: $info")
                                        point.essid = info["ESSID"] as? String
                                        point.password = info["WiFiKey"] as? String
                                        point.wpsPin = info["WPSPIN"]?.toString()
                                        point.isDataLoaded = true
                                    } else {
                                        Log.w(TAG, "No info found from SQLite3WiFiHelper")
                                    }
                                }
                                is SQLiteCustomHelper -> {
                                    if (database.tableName != null && database.columnMap != null) {
                                        Log.d(TAG, "Searching network by BSSID in SQLiteCustomHelper")
                                        val bssidStr = convertBssidToString(point.bssidDecimal)

                                        val info = helper.searchNetworksByBSSIDAndFields(
                                            database.tableName,
                                            database.columnMap,
                                            bssidStr,
                                            setOf("mac"),
                                            true
                                        ).firstOrNull()

                                        if (info != null) {
                                            Log.d(TAG, "Retrieved info from SQLiteCustomHelper: $info")

                                            point.essid = info["essid"] as? String
                                            point.password = info["wifi_pass"] as? String
                                            point.wpsPin = info["wps_pin"]?.toString()
                                            point.isDataLoaded = true
                                        } else {
                                            Log.w(TAG, "No info found from SQLiteCustomHelper for BSSID: $bssidStr")
                                        }
                                    } else {
                                        Log.e(TAG, "Table name or column map is null for database ${database.id}")
                                    }
                                }
                                else -> {
                                    Log.e(TAG, "Unknown helper type: ${helper.javaClass.name}")
                                }
                            }
                        }
                    }

                    Log.d(TAG, "Posting selected point with ESSID: ${point.essid}, isDataLoaded: ${point.isDataLoaded}")
                    _selectedPoint.postValue(point)
                } else {
                    Log.e(TAG, "Database not found for point with BSSID: ${convertBssidToString(point.bssidDecimal)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading point info", e)
                _error.postValue(getApplication<Application>().getString(R.string.point_loading_error))
            }
        }
    }



    override fun onCleared() {
        super.onCleared()
        clearCache()
        viewModelScope.launch(Dispatchers.IO) {
            databaseHelpers.values.forEach { it.close() }
            databaseHelpers.clear()
        }
    }
}