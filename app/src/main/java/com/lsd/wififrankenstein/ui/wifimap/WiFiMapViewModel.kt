package com.lsd.wififrankenstein.ui.wifimap

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import com.lsd.wififrankenstein.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
    private var currentLoadingJob: Job? = null
    private var backgroundCachingJobs = mutableMapOf<String, Job>()

    private val MIN_UPDATE_INTERVAL = 300L

    private val _databaseColors = mutableMapOf<String, Int>()
    private val availableColors = listOf(
        Color.RED,
        Color.BLUE,
        Color.GREEN,
        Color.rgb(139, 69, 19),
        Color.MAGENTA,
        Color.CYAN,
        Color.rgb(255, 165, 0),
        Color.rgb(128, 0, 128),
        Color.rgb(165, 42, 42),
        Color.rgb(255, 192, 203)
    )
    private var nextColorIndex = 0

    private val _databaseColorsLiveData = MutableLiveData<Map<String, Int>>()
    val databaseColors: LiveData<Map<String, Int>> = _databaseColorsLiveData

    private data class CacheEntry(
        val points: List<NetworkPoint>,
        val boundingBox: BoundingBox,
        val zoom: Double,
        val wasLimited: Boolean
    )

    private var backgroundCachingJob: Job? = null

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

    private fun launchBackgroundCaching(database: DbItem, currentBox: BoundingBox, zoom: Double) {
        backgroundCachingJob?.cancel()

        backgroundCachingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting background caching for ${database.id} at zoom $zoom")

                val latRange = currentBox.latNorth - currentBox.latSouth
                val lonRange = currentBox.lonEast - currentBox.lonWest

                val neighborBoxes = listOf(
                    BoundingBox(
                        currentBox.latNorth + latRange, currentBox.lonEast,
                        currentBox.latNorth, currentBox.lonWest
                    ),
                    BoundingBox(
                        currentBox.latNorth + latRange, currentBox.lonEast + lonRange,
                        currentBox.latNorth, currentBox.lonEast
                    ),
                    BoundingBox(
                        currentBox.latNorth + latRange, currentBox.lonWest,
                        currentBox.latNorth, currentBox.lonWest - lonRange
                    ),
                    BoundingBox(
                        currentBox.latNorth, currentBox.lonWest,
                        currentBox.latSouth, currentBox.lonWest - lonRange
                    ),
                    BoundingBox(
                        currentBox.latNorth, currentBox.lonEast + lonRange,
                        currentBox.latSouth, currentBox.lonEast
                    ),
                    BoundingBox(
                        currentBox.latSouth, currentBox.lonWest,
                        currentBox.latSouth - latRange, currentBox.lonWest - lonRange
                    ),
                    BoundingBox(
                        currentBox.latSouth, currentBox.lonEast,
                        currentBox.latSouth - latRange, currentBox.lonWest
                    ),
                    BoundingBox(
                        currentBox.latSouth, currentBox.lonEast + lonRange,
                        currentBox.latSouth - latRange, currentBox.lonEast
                    )
                )

                neighborBoxes.forEachIndexed { index, neighborBox ->
                    if (!isActive) return@forEachIndexed

                    val neighborCacheKey = "${database.id}_${createCacheKey(neighborBox)}"

                    if (!smartCache.containsKey(neighborCacheKey)) {
                        Log.d(TAG, "Background caching neighbor area ${index + 1}/8 for ${database.id}")

                        try {
                            backgroundLoadArea(database, neighborBox, zoom)

                            delay(100)

                        } catch (e: Exception) {
                            Log.w(TAG, "Background caching failed for neighbor area ${index + 1}: ${e.message}")
                        }
                    }
                }

                Log.d(TAG, "Background caching completed for ${database.id}")

            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Background caching error for ${database.id}", e)
                }
            }
        }
    }

    private suspend fun backgroundLoadArea(database: DbItem, boundingBox: BoundingBox, zoom: Double) {
        val cacheKey = "${database.id}_${createCacheKey(boundingBox)}"
        val maxPoints = getMaxPointsForZoom(zoom)
        val willBeLimited = zoom < 12.0 && maxPoints != Int.MAX_VALUE

        val points = when (database.dbType) {
            DbType.LOCAL_APP_DB -> {
                val dbHelper = LocalAppDbHelper(getApplication())
                val localPoints = dbHelper.getPointsInBounds(
                    boundingBox.latSouth,
                    boundingBox.latNorth,
                    boundingBox.lonWest,
                    boundingBox.lonEast
                )

                localPoints.mapIndexed { index, network ->
                    if (index % 1000 == 0) {
                        yield()
                    }

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
                    emptyList()
                } else {
                    if (willBeLimited) maxPoints else Int.MAX_VALUE
                    externalIndexManager.getPointsInBoundingBox(database.id, boundingBox)
                }
            }
            else -> {
                val helper = getHelper(database)
                when (helper) {
                    is SQLite3WiFiHelper -> {
                        if (willBeLimited) maxPoints else Int.MAX_VALUE
                        helper.getPointsInBoundingBox(boundingBox)
                    }
                    is SQLiteCustomHelper -> {
                        if (database.tableName != null && database.columnMap != null) {
                            if (willBeLimited) maxPoints else Int.MAX_VALUE
                            helper.getPointsInBoundingBox(
                                boundingBox,
                                database.tableName,
                                database.columnMap
                            ) ?: emptyList()
                        } else {
                            emptyList()
                        }
                    }
                    else -> emptyList()
                }
            }
        }

        if (points.isNotEmpty()) {
            val networkPoints = points.mapIndexed { index, (bssid, lat, lon) ->
                if (index % 1000 == 0) {
                    yield()
                }
                NetworkPoint(
                    latitude = lat,
                    longitude = lon,
                    bssidDecimal = bssid,
                    source = database.type,
                    databaseId = database.id
                )
            }

            val cacheEntry = CacheEntry(
                points = networkPoints,
                boundingBox = boundingBox,
                zoom = zoom,
                wasLimited = willBeLimited && networkPoints.size >= maxPoints
            )

            smartCache[cacheKey] = cacheEntry
            Log.d(TAG, "Background cached ${networkPoints.size} points for ${database.id} (wasLimited: ${cacheEntry.wasLimited})")
        }
    }

    private val smartCache = mutableMapOf<String, CacheEntry>()

    fun getMinZoomForMarkers(): Double {
        val zoomSetting = settingsPrefs.getFloat("map_marker_visibility_zoom", 11f)
        val result = zoomSetting.toDouble().coerceIn(1.0, 18.0)
        Log.d(TAG, "getMinZoomForMarkers: setting=$zoomSetting, result=$result")
        return result
    }

    init {
        viewModelScope.launch {
            dbSetupViewModel.dbList.observeForever { dbList ->
                val databases = dbList ?: emptyList()
                _availableDatabases.value = databases
                assignColorsToDatabase(databases)
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

    fun updateClusterSettings() {
        clusterManager = getClusterManager()
        clearCache()
    }

    private fun getClusterManager(): GridBasedClusterManager {
        val maxClusterSize = settingsPrefs.getInt("map_max_cluster_size", 5000)
        val clusterAggressiveness = settingsPrefs.getFloat("map_cluster_aggressiveness", 0.4f)
        val preventMerge = settingsPrefs.getBoolean("map_prevent_cluster_merge", false)
        val forceSeparation = settingsPrefs.getBoolean("map_force_point_separation", true)
        return GridBasedClusterManager(maxClusterSize, clusterAggressiveness, preventMerge, forceSeparation)
    }

    private fun createCacheKey(boundingBox: BoundingBox): String {
        val latSouth = (boundingBox.latSouth * 100).toInt() / 100.0
        val latNorth = (boundingBox.latNorth * 100).toInt() / 100.0
        val lonWest = (boundingBox.lonWest * 100).toInt() / 100.0
        val lonEast = (boundingBox.lonEast * 100).toInt() / 100.0
        return "$latSouth,$latNorth,$lonWest,$lonEast"
    }

    private fun isAreaContainedIn(innerBox: BoundingBox, outerBox: BoundingBox): Boolean {
        return innerBox.latSouth >= outerBox.latSouth &&
                innerBox.latNorth <= outerBox.latNorth &&
                innerBox.lonWest >= outerBox.lonWest &&
                innerBox.lonEast <= outerBox.lonEast
    }

    private fun getAreaIntersection(box1: BoundingBox, box2: BoundingBox): BoundingBox? {
        val latSouth = maxOf(box1.latSouth, box2.latSouth)
        val latNorth = minOf(box1.latNorth, box2.latNorth)
        val lonWest = maxOf(box1.lonWest, box2.lonWest)
        val lonEast = minOf(box1.lonEast, box2.lonEast)

        return if (latSouth < latNorth && lonWest < lonEast) {
            BoundingBox(latNorth, lonEast, latSouth, lonWest)
        } else {
            null
        }
    }

    fun updateClusterManager() {
        clusterManager = getClusterManager()
        clearCache()
    }

    private fun getIntersectionArea(box: BoundingBox): Double {
        return (box.latNorth - box.latSouth) * (box.lonEast - box.lonWest)
    }

    private fun findContainingCache(
        databaseId: String,
        requestedBox: BoundingBox,
        requestedZoom: Double
    ): CacheEntry? {
        for ((cacheKey, entry) in smartCache) {
            if (entry.points.any { it.databaseId == databaseId } && !entry.wasLimited) {

                if (isAreaContainedIn(requestedBox, entry.boundingBox)) {
                    Log.d(TAG, "Found FULL containing cache for $databaseId: zoom ${entry.zoom}")
                    return entry
                }

                val intersection = getAreaIntersection(requestedBox, entry.boundingBox)
                if (intersection != null) {
                    val requestedArea = getIntersectionArea(requestedBox)
                    val intersectionArea = getIntersectionArea(intersection)
                    val coveragePercent = (intersectionArea / requestedArea) * 100

                    Log.d(TAG, "Found PARTIAL cache for $databaseId: ${String.format("%.1f", coveragePercent)}% coverage")

                    if (coveragePercent >= 70.0) {
                        Log.d(TAG, "Using partial cache (${String.format("%.1f", coveragePercent)}% >= 70%)")
                        return entry
                    } else {
                        Log.d(TAG, "Skipping partial cache (${String.format("%.1f", coveragePercent)}% < 70%)")
                    }
                }
            }
        }
        return null
    }

    private fun filterPointsInBounds(points: List<NetworkPoint>, bounds: BoundingBox): List<NetworkPoint> {
        return points.filter { point ->
            point.latitude >= bounds.latSouth &&
                    point.latitude <= bounds.latNorth &&
                    point.longitude >= bounds.lonWest &&
                    point.longitude <= bounds.lonEast
        }
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

    private fun checkMemoryAndCleanup() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = maxMemory - usedMemory

        if (availableMemory < maxMemory * 0.2) {
            Log.w(TAG, "Low memory detected, clearing caches")
            clearCache()
            System.gc()
        }
    }

    suspend fun loadPointsInBoundingBox(
        boundingBox: BoundingBox,
        zoom: Double,
        selectedDatabases: Set<DbItem>
    ) {
        Log.d(TAG, "loadPointsInBoundingBox called: zoom=$zoom, databases=${selectedDatabases.size}")

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < MIN_UPDATE_INTERVAL) {
            Log.d(TAG, "Skipping update - too soon (${currentTime - lastUpdateTime}ms < ${MIN_UPDATE_INTERVAL}ms)")
            return
        }
        lastUpdateTime = currentTime

        val minZoom = getMinZoomForMarkers()
        Log.d(TAG, "Zoom check: current=$zoom, required=$minZoom")

        if (zoom < minZoom) {
            Log.d(TAG, "Zoom level too low: $zoom < $minZoom")
            _points.value = emptyList()
            return
        }

        Log.d(TAG, "Proceeding with point loading...")

        currentLoadingJob?.cancel()
        backgroundCachingJobs.values.forEach { it.cancel() }
        backgroundCachingJobs.clear()

        currentLoadingJob = viewModelScope.launch {
            try {
                _loadingProgress.postValue(0)

                val expandedBoundingBox = expandBoundingBoxForPreload(boundingBox, zoom)

                val totalMaxPoints = getMaxPointsForZoom(zoom)
                val basePointsPerDatabase = if (totalMaxPoints == Int.MAX_VALUE) {
                    Int.MAX_VALUE
                } else {
                    maxOf(2000, totalMaxPoints / selectedDatabases.size)
                }

                Log.d(TAG, "Total max points: $totalMaxPoints, Base points per database: $basePointsPerDatabase")

                val databasePointCounts = mutableMapOf<String, Int>()

                val allPoints = withContext(MapOperationExecutor.databaseDispatcher) {
                    val points = Collections.synchronizedList(mutableListOf<NetworkPoint>())
                    val totalDatabases = selectedDatabases.size

                    val firstPassJobs = selectedDatabases.mapIndexed { index, database ->
                        async {
                            if (!isActive) return@async

                            try {
                                val quickSamplePoints = loadSamplePointsFromDatabase(database, expandedBoundingBox, 100)
                                val estimatedTotal = quickSamplePoints.size * 100
                                databasePointCounts[database.id] = estimatedTotal

                                val progress = ((index + 1) * 10) / totalDatabases
                                _loadingProgress.postValue(progress)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sampling points from database ${database.id}", e)
                                databasePointCounts[database.id] = 0
                            }
                        }
                    }

                    firstPassJobs.awaitAll()

                    val minCount = databasePointCounts.values.minOrNull() ?: 1
                    val maxCount = databasePointCounts.values.maxOrNull() ?: 1

                    val adjustedLimits = selectedDatabases.associate { database ->
                        val dbCount = databasePointCounts[database.id] ?: 1
                        val priorityMultiplier = if (dbCount <= minCount * 2) 2.0 else 1.0
                        val adjustedLimit = (basePointsPerDatabase * priorityMultiplier).toInt()
                        database.id to minOf(adjustedLimit, if (totalMaxPoints == Int.MAX_VALUE) Int.MAX_VALUE else totalMaxPoints)
                    }

                    Log.d(TAG, "Database point counts: $databasePointCounts")
                    Log.d(TAG, "Adjusted limits: $adjustedLimits")

                    val secondPassJobs = selectedDatabases.mapIndexed { index, database ->
                        async {
                            if (!isActive) return@async

                            try {
                                val startTime = System.currentTimeMillis()
                                val limit = adjustedLimits[database.id] ?: basePointsPerDatabase
                                val dbPoints = loadPointsFromDatabase(database, expandedBoundingBox, zoom, limit)
                                val loadTime = System.currentTimeMillis() - startTime

                                if (!isActive) return@async

                                val networkPoints = dbPoints.map { (bssidDecimal, lat, lon) ->
                                    NetworkPoint(
                                        latitude = lat,
                                        longitude = lon,
                                        bssidDecimal = bssidDecimal,
                                        source = database.type,
                                        databaseId = database.id,
                                        color = getColorForDatabase(database.id)
                                    )
                                }.filter { point ->
                                    point.latitude >= boundingBox.latSouth * 0.98 &&
                                            point.latitude <= boundingBox.latNorth * 1.02 &&
                                            point.longitude >= boundingBox.lonWest * 0.98 &&
                                            point.longitude <= boundingBox.lonEast * 1.02
                                }

                                points.addAll(networkPoints)

                                val progress = 10 + ((index + 1) * 30) / totalDatabases
                                _loadingProgress.postValue(progress)

                                Log.d(TAG, "Loaded ${networkPoints.size} points from ${database.id} (limit: $limit) in ${loadTime}ms")
                            } catch (e: CancellationException) {
                                Log.d(TAG, "Database loading cancelled for ${database.id}")
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Error loading points from database ${database.id}", e)
                            }
                        }
                    }

                    try {
                        secondPassJobs.awaitAll()
                    } catch (e: CancellationException) {
                        Log.d(TAG, "All database loading cancelled")
                        throw e
                    }

                    Log.d(TAG, "Combined points from all databases: ${points.size}")

                    val pointsByDatabase = points.groupBy { it.databaseId }
                    pointsByDatabase.forEach { (dbId, dbPoints) ->
                        Log.d(TAG, "Database $dbId final count: ${dbPoints.size}")
                    }

                    points.toList()
                }

                if (!isActive) return@launch

                _loadingProgress.postValue(50)

                if (allPoints.isNotEmpty()) {
                    val clusteredPoints = withContext(MapOperationExecutor.clusteringDispatcher) {
                        if (!isActive) return@withContext emptyList()

                        val mapCenter = Pair(
                            (boundingBox.latNorth + boundingBox.latSouth) / 2,
                            (boundingBox.lonEast + boundingBox.lonWest) / 2
                        )

                        _loadingProgress.postValue(60)

                        val clusters = clusterManager.createAdaptiveClusters(allPoints, zoom, mapCenter)

                        _loadingProgress.postValue(75)

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
                                    color = getColorForDatabase(dominantDatabaseId)
                                )
                            }
                        }
                    }

                    if (!isActive) return@launch

                    withContext(MapOperationExecutor.uiUpdateDispatcher) {
                        _loadingProgress.postValue(90)
                        _points.postValue(clusteredPoints)
                        _loadingProgress.postValue(100)
                    }
                } else {
                    withContext(MapOperationExecutor.uiUpdateDispatcher) {
                        _points.postValue(emptyList())
                        _loadingProgress.postValue(100)
                    }
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Load points cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading points", e)
                _error.postValue(getApplication<Application>().getString(
                    R.string.database_error,
                    e.localizedMessage ?: "Unknown error"
                ))
            } finally {
                withContext(MapOperationExecutor.uiUpdateDispatcher) {
                    _loadingProgress.postValue(100)
                }
            }
        }
    }

    private suspend fun loadSamplePointsFromDatabase(
        database: DbItem,
        boundingBox: BoundingBox,
        sampleSize: Int
    ): List<Triple<Long, Double, Double>> = withContext(Dispatchers.IO) {
        try {
            when (database.dbType) {
                DbType.LOCAL_APP_DB -> {
                    val dbHelper = LocalAppDbHelper(getApplication())
                    val localPoints = dbHelper.getPointsInBounds(
                        boundingBox.latSouth,
                        boundingBox.latNorth,
                        boundingBox.lonWest,
                        boundingBox.lonEast,
                        sampleSize
                    )
                    localPoints.map { network ->
                        val macDecimal = try {
                            network.macAddress.replace(":", "").replace("-", "").toLongOrNull(16) ?: -1L
                        } catch (_: Exception) { -1L }
                        Triple(macDecimal, network.latitude ?: 0.0, network.longitude ?: 0.0)
                    }.filter { it.first != -1L }
                }
                DbType.SQLITE_FILE_CUSTOM -> {
                    if (database.directPath.isNullOrEmpty()) {
                        emptyList()
                    } else {
                        externalIndexManager.getPointsInBoundingBox(database.id, boundingBox, sampleSize)
                    }
                }
                else -> {
                    val helper = getHelper(database)
                    when (helper) {
                        is SQLite3WiFiHelper -> helper.getPointsInBoundingBox(boundingBox, sampleSize)
                        is SQLiteCustomHelper -> {
                            if (database.tableName != null && database.columnMap != null) {
                                helper.getPointsInBoundingBox(
                                    boundingBox,
                                    database.tableName,
                                    database.columnMap,
                                    sampleSize
                                ) ?: emptyList()
                            } else {
                                emptyList()
                            }
                        }
                        else -> emptyList()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading sample points from ${database.id}", e)
            emptyList()
        }
    }

    private fun expandBoundingBoxForPreload(originalBox: BoundingBox, zoom: Double): BoundingBox {
        val latRange = originalBox.latNorth - originalBox.latSouth
        val lonRange = originalBox.lonEast - originalBox.lonWest

        val expansionFactor = when {
            zoom >= 14.0 -> 0.8
            zoom >= 12.0 -> 0.6
            zoom >= 10.0 -> 0.4
            zoom >= 8.0 -> 0.3
            else -> 0.2
        }

        val latExpansion = latRange * expansionFactor
        val lonExpansion = lonRange * expansionFactor

        val expandedBox = BoundingBox(
            originalBox.latNorth + latExpansion,
            originalBox.lonEast + lonExpansion,
            originalBox.latSouth - latExpansion,
            originalBox.lonWest - lonExpansion
        )

        Log.d(TAG, "Expanded bounding box by ${expansionFactor * 100}% for zoom $zoom")
        return expandedBox
    }

    private fun getMaxPointsForZoom(zoom: Double): Int {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val memoryClass = when {
            maxMemory < 128 * 1024 * 1024 -> "LOW"
            maxMemory < 256 * 1024 * 1024 -> "MEDIUM"
            else -> "HIGH"
        }

        val maxPoints = when (memoryClass) {
            "LOW" -> when {
                zoom >= 14.0 -> Int.MAX_VALUE
                zoom >= 12.0 -> 15000
                zoom >= 10.0 -> 10000
                zoom >= 8.0 -> 8000
                else -> 6000
            }
            "MEDIUM" -> when {
                zoom >= 13.0 -> Int.MAX_VALUE
                zoom >= 11.0 -> 25000
                zoom >= 9.0 -> 20000
                zoom >= 7.0 -> 15000
                else -> 12000
            }
            else -> when {
                zoom >= 12.0 -> Int.MAX_VALUE
                zoom >= 10.0 -> 50000
                zoom >= 8.0 -> 40000
                else -> 30000
            }
        }

        Log.d(TAG, "Memory class: $memoryClass, Max points PER DATABASE for zoom $zoom: ${if (maxPoints == Int.MAX_VALUE) "UNLIMITED" else maxPoints}")
        return maxPoints
    }

    private suspend fun loadPointsFromDatabase(
        database: DbItem,
        boundingBox: BoundingBox,
        zoom: Double,
        maxPointsPerDb: Int
    ): List<Triple<Long, Double, Double>> = withContext(Dispatchers.IO) {
        val cacheKey = "${database.id}_${createCacheKey(boundingBox)}"
        val maxPointsPerDb = getMaxPointsForZoom(zoom)
        val willBeLimited = zoom < 12.0 && maxPointsPerDb != Int.MAX_VALUE

        Log.d(TAG, "Loading from DB: ${database.id}, maxPointsPerDb: $maxPointsPerDb, zoom: $zoom, willBeLimited: $willBeLimited")

        if (zoom < 14.0 && clusterManager.canUsePointsCache(boundingBox) &&
            !clusterManager.hasSignificantAreaChange(boundingBox)) {
            Log.d(TAG, "Using cached points for ${database.id}")
            val cachedPoints = clusterManager.getCachedPointsInBounds(boundingBox)
                .filter { it.databaseId == database.id }

            if (cachedPoints.isNotEmpty()) {
                Log.d(TAG, "Found ${cachedPoints.size} cached points for ${database.id}")
                return@withContext cachedPoints.take(maxPointsPerDb).map {
                    Triple(it.bssidDecimal, it.latitude, it.longitude)
                }
            }
        }

        smartCache[cacheKey]?.let { cacheEntry ->
            Log.d(TAG, "Cache hit: ${cacheEntry.points.size} cached points for ${database.id}")
            val limitedPoints = if (willBeLimited && cacheEntry.points.size > maxPointsPerDb) {
                Log.d(TAG, "Limiting cached points for ${database.id}: ${cacheEntry.points.size} -> $maxPointsPerDb")
                cacheEntry.points.take(maxPointsPerDb)
            } else {
                cacheEntry.points
            }

            if (zoom >= 10.0) {
                launchBackgroundCaching(database, boundingBox, zoom)
            }

            return@withContext limitedPoints.map {
                Triple(it.bssidDecimal, it.latitude, it.longitude)
            }
        }

        Log.d(TAG, "Cache miss for ${database.id}, loading from database with limit $maxPointsPerDb per database")

        val containingCache = findContainingCache(database.id, boundingBox, zoom)
        if (containingCache != null) {
            Log.d(TAG, "Using smart cache: filtering ${containingCache.points.size} points from larger area for ${database.id}")
            val filteredPoints = filterPointsInBounds(
                containingCache.points.filter { it.databaseId == database.id },
                boundingBox
            ).take(maxPointsPerDb)
            Log.d(TAG, "Smart cache result for ${database.id}: ${filteredPoints.size} points after filtering")

            if (zoom >= 10.0) {
                launchBackgroundCaching(database, boundingBox, zoom)
            }

            return@withContext filteredPoints.map {
                Triple(it.bssidDecimal, it.latitude, it.longitude)
            }
        }

        Log.d(TAG, "Cache miss for ${database.id}, loading from database")

        val points = when (database.dbType) {
            DbType.LOCAL_APP_DB -> {
                val dbHelper = LocalAppDbHelper(getApplication())
                val localPoints = dbHelper.getPointsInBounds(
                    boundingBox.latSouth,
                    boundingBox.latNorth,
                    boundingBox.lonWest,
                    boundingBox.lonEast,
                    maxPointsPerDb
                )

                Log.d(TAG, "Local DB returned ${localPoints.size} points for ${database.id}")

                localPoints.mapIndexed { index, network ->
                    if (index % 1000 == 0) {
                        yield()
                    }

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
                    val allPoints = externalIndexManager.getPointsInBoundingBox(database.id, boundingBox, maxPointsPerDb)
                    Log.d(TAG, "External index returned ${allPoints.size} points for ${database.id}")
                    allPoints
                }
            }
            else -> {
                val helper = getHelper(database)
                when (helper) {
                    is SQLite3WiFiHelper -> {
                        Log.d(TAG, "Using SQLite3WiFiHelper for ${database.id}")
                        val allPoints = helper.getPointsInBoundingBox(boundingBox, maxPointsPerDb)
                        Log.d(TAG, "SQLite3WiFiHelper returned ${allPoints.size} points for ${database.id}")
                        allPoints
                    }
                    is SQLiteCustomHelper -> {
                        if (database.tableName == null || database.columnMap == null) {
                            Log.e(TAG, "Table name or column map is null for database ${database.id}")
                            emptyList()
                        } else {
                            Log.d(TAG, "Using SQLiteCustomHelper for ${database.id}")
                            val allPoints = helper.getPointsInBoundingBox(
                                boundingBox,
                                database.tableName,
                                database.columnMap,
                                maxPointsPerDb
                            ) ?: emptyList()
                            Log.d(TAG, "SQLiteCustomHelper returned ${allPoints.size} points for ${database.id}")
                            allPoints
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
            val networkPoints = points.mapIndexed { index, (bssid, lat, lon) ->
                if (index % 1000 == 0) {
                    yield()
                }
                NetworkPoint(
                    latitude = lat,
                    longitude = lon,
                    bssidDecimal = bssid,
                    source = database.type,
                    databaseId = database.id
                )
            }

            val finalPoints = if (willBeLimited && networkPoints.size > maxPointsPerDb) {
                Log.d(TAG, "Limiting points for ${database.id}: ${networkPoints.size} -> $maxPointsPerDb")
                networkPoints.take(maxPointsPerDb)
            } else {
                networkPoints
            }

            val cacheEntry = CacheEntry(
                points = finalPoints,
                boundingBox = boundingBox,
                zoom = zoom,
                wasLimited = willBeLimited && finalPoints.size >= maxPointsPerDb
            )
            smartCache[cacheKey] = cacheEntry

            Log.d(TAG, "Cached ${finalPoints.size} points for ${database.id} (wasLimited: ${cacheEntry.wasLimited})")

            if (zoom >= 10.0) {
                launchBackgroundCaching(database, boundingBox, zoom)
            }

            return@withContext finalPoints.map {
                Triple(it.bssidDecimal, it.latitude, it.longitude)
            }
        }

        emptyList()
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
        currentLoadingJob?.cancel()
        backgroundCachingJobs.values.forEach { it.cancel() }
        backgroundCachingJobs.clear()

        pointsCache.clear()
        smartCache.clear()
        clusterManager.clearAllCache()

        lastUpdateTime = 0L
        Log.d(TAG, "Cleared all caches and cancelled background jobs")
    }

    fun forceRefresh() {
        clearCache()
        clusterManager.forceRefresh()
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
                    assignColorsToDatabase(dbList)
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
                                    val password = database.columnMap?.get("wifi_pass")?.let { colName ->
                                        info[colName]?.toString()
                                    }
                                    val wpsPin = database.columnMap?.get("wps_pin")?.let { colName ->
                                        info[colName]?.toString()
                                    }
                                    val routerModel = info["name"]?.toString()
                                    val authData = info["Authorization"]?.toString() ?: info["admin_panel"]?.toString()
                                    val hiddenData = info["Hidden"]?.toString()
                                    val radioOffData = info["RadioOff"]?.toString()
                                    val timeData = info["time"]?.toString() ?: info["timestamp"]?.toString()

                                    NetworkRecord(
                                        essid = essid ?: "Unknown SSID",
                                        password = password,
                                        wpsPin = wpsPin,
                                        routerModel = routerModel,
                                        adminCredentials = parseAdminCredentials(authData),
                                        isHidden = parseHiddenStatus(hiddenData),
                                        isWifiDisabled = parseWifiDisabledStatus(radioOffData),
                                        timeAdded = timeData,
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

                            Log.d(TAG, "Using helper ${helper.javaClass.simpleName} for getting point info")

                            when (helper) {
                                is SQLite3WiFiHelper -> {
                                    val info = helper.loadNetworkInfo(point.bssidDecimal)

                                    if (info != null) {
                                        Log.d(TAG, "Retrieved info from SQLite3WiFiHelper: $info")
                                        val record = NetworkRecord(
                                            essid = info["ESSID"] as? String,
                                            password = info["WiFiKey"] as? String,
                                            wpsPin = info["WPSPIN"]?.toString(),
                                            routerModel = info["name"] as? String,
                                            adminCredentials = parseAdminCredentials(info["Authorization"] as? String),
                                            isHidden = info["Hidden"]?.toString() == "b1",
                                            isWifiDisabled = info["RadioOff"]?.toString() == "b1",
                                            timeAdded = info["time"] as? String,
                                            rawData = info
                                        )

                                        point.allRecords = listOf(record)
                                        point.essid = record.essid
                                        point.password = record.password
                                        point.wpsPin = record.wpsPin
                                        point.routerModel = record.routerModel
                                        point.isHidden = record.isHidden
                                        point.isWifiDisabled = record.isWifiDisabled
                                        point.isDataLoaded = true
                                    } else {
                                        Log.w(TAG, "No info found from SQLite3WiFiHelper")
                                    }
                                }
                                is SQLiteCustomHelper -> {
                                    if (database.tableName != null && database.columnMap != null) {
                                        Log.d(TAG, "Searching network by BSSID in SQLiteCustomHelper")
                                        val bssidStr = convertBssidToString(point.bssidDecimal)

                                        val infoList = helper.searchNetworksByBSSIDAndFields(
                                            database.tableName,
                                            database.columnMap,
                                            bssidStr,
                                            setOf("mac"),
                                            true
                                        )

                                        if (infoList.isNotEmpty()) {
                                            Log.d(TAG, "Retrieved ${infoList.size} records from SQLiteCustomHelper")

                                            val records = infoList.map { info ->
                                                val essid = database.columnMap["essid"]?.let { info[it]?.toString() }
                                                val password = database.columnMap["wifi_pass"]?.let { info[it]?.toString() }
                                                val wpsPin = database.columnMap["wps_pin"]?.let { info[it]?.toString() }
                                                val routerModel = info["name"]?.toString()
                                                val authData = info["Authorization"]?.toString()
                                                val hiddenData = info["Hidden"]?.toString()
                                                val radioOffData = info["RadioOff"]?.toString()
                                                val timeData = info["time"]?.toString()

                                                NetworkRecord(
                                                    essid = essid,
                                                    password = password,
                                                    wpsPin = wpsPin,
                                                    routerModel = routerModel,
                                                    adminCredentials = parseAdminCredentials(authData),
                                                    isHidden = hiddenData == "b1",
                                                    isWifiDisabled = radioOffData == "b1",
                                                    timeAdded = timeData,
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

    private fun parseHiddenStatus(value: String?): Boolean {
        return when (value?.lowercase()?.trim()) {
            "b1", "1", "true", "yes" -> true
            else -> false
        }
    }

    private fun parseWifiDisabledStatus(value: String?): Boolean {
        return when (value?.lowercase()?.trim()) {
            "b1", "1", "true", "yes" -> true
            else -> false
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

    override fun onCleared() {
        super.onCleared()
        backgroundCachingJob?.cancel()
        clearCache()
        viewModelScope.launch(Dispatchers.IO) {
            databaseHelpers.values.forEach { it.close() }
            databaseHelpers.clear()
        }
    }
}