package com.lsd.wififrankenstein.ui.dbsetup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Looper
import com.lsd.wififrankenstein.ui.databasefinder.AdvancedSearchQuery
import com.lsd.wififrankenstein.ui.databasefinder.SearchMode
import com.lsd.wififrankenstein.ui.ipranges.IpRangeManager
import com.lsd.wififrankenstein.ui.wifimap.ClusteredMapPoint
import com.lsd.wififrankenstein.util.CompatibilityHelper
import com.lsd.wififrankenstein.util.DatabaseIndices
import com.lsd.wififrankenstein.util.DatabaseOptimizer
import com.lsd.wififrankenstein.util.DatabaseTypeUtils
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.PerformanceManager
import com.lsd.wififrankenstein.util.QuadkeyUtils
import com.lsd.wififrankenstein.util.TileRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.osmdroid.util.BoundingBox
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap


class SQLite3WiFiHelper(
    private val context: Context,
    private val dbUri: Uri,
    private val directPath: String?,
    deferOpen: Boolean = false
) : SQLiteOpenHelper(context, null, null, 1) {
    var database: SQLiteDatabase? = null
    private var selectedFileSize: Float = 0f
    private val databaseLock = Mutex()

    private val cacheDir = File(context.cacheDir, "CacheDB").apply { mkdirs() }

    private var cachedIpRangeManager: IpRangeManager? = null
    private val ipRangeManager: IpRangeManager
        get() {
            return cachedIpRangeManager ?: run {
                val manager = IpRangeManager(context)
                cachedIpRangeManager = manager
                manager
            }
        }

    init {
        if (!deferOpen) {
            Log.d(
                TAG,
                "Initializing SQLite3WiFiHelper for directPath=$directPath, uri=${dbUri.path}"
            )

            if (!directPath.isNullOrBlank()) {
                Log.d(TAG, "Attempting to open database from direct path: $directPath")
                try {
                    database = openDatabaseFromDirectPath()
                    Log.d(TAG, "Successfully opened database from direct path")
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Failed to open database from direct path: ${e.message}, falling back to URI method",
                        e
                    )
                    database = null
                }
            } else {
                Log.d(TAG, "No direct path provided, using URI method")
            }

            if (database == null) {
                try {
                    database = openDatabaseFromUri()
                    Log.d(TAG, "Successfully opened database from URI method")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open database from URI method", e)
                    database = null
                }
            }
        } else {
            Log.d(TAG, "Deferred opening - will open via copyAndOpenWithProgress")
        }
    }

    suspend fun copyAndOpenWithProgress(
        onProgress: (Int, Long, Long) -> Unit,
        onCancel: () -> Unit
    ): Boolean {
        if (database != null) {
            return true
        }

        Log.d(TAG, "Starting copy and open with progress")

        return try {
            database = copyUriToCacheWithProgress(onProgress)
            if (database != null) {
                Log.d(TAG, "Successfully copied and opened database from URI to cache")
            } else {
                Log.e(TAG, "Failed to copy database - result is null")
            }
            database != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy and open database", e)
            throw e
        }
    }

    private suspend fun copyUriToCacheWithProgress(onProgress: (Int, Long, Long) -> Unit): SQLiteDatabase {
        val fileSize =
            context.contentResolver.openFileDescriptor(dbUri, "r")?.use { it.statSize } ?: 0L
        val fileName = getFileNameFromUri(dbUri)
        val tempFile = File(cacheDir, fileName)
        var totalBytes = 0L
        var lastProgressReport = -1

        try {
            context.contentResolver.openInputStream(dbUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val bufferSize = if (fileSize > 500 * 1024 * 1024) 131072 else 65536
                    val buffer = ByteArray(bufferSize)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        val progress = if (fileSize > 0) {
                            (totalBytes * 100 / fileSize).toInt()
                        } else {
                            -1
                        }

                        if (progress != lastProgressReport && progress > 0) {
                            lastProgressReport = progress
                            onProgress(progress, totalBytes, fileSize)
                        }
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                throw IllegalStateException("Failed to copy file: empty or missing")
            }

            selectedFileSize = tempFile.length().toFloat() / (1024 * 1024)
            return SQLiteDatabase.openDatabase(
                tempFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                SafeDatabaseErrorHandler()
            )
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private val resultsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Map<String, Any?>>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Any?>?>): Boolean {
                return size > 1000
            }
        }
    )
    private val validSearchColumnsCache = ConcurrentHashMap<String, Pair<String, String>>()

    var hasQuadkey: Boolean = false
        private set

    var indexLevel: DatabaseIndices.IndexLevel = DatabaseIndices.IndexLevel.NONE
        private set

    var corruptionDetected: Boolean = false
        private set

    private fun checkSqliteIntegrity(filePath: String): Boolean {
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                if (raf.length() < 100) return false
                val header = ByteArray(100)
                raf.readFully(header)

                val pageSize =
                    ((header[16].toInt() and 0xFF) shl 8) or (header[17].toInt() and 0xFF)
                val effectivePageSize = if (pageSize == 1) 65536 else pageSize

                val pageCount = ((header[28].toInt() and 0xFF) shl 24) or
                        ((header[29].toInt() and 0xFF) shl 16) or
                        ((header[30].toInt() and 0xFF) shl 8) or
                        (header[31].toInt() and 0xFF)

                val expectedSize = pageCount.toLong() * effectivePageSize
                val actualSize = raf.length()

                val valid = actualSize >= expectedSize
                if (!valid) {
                    Log.w(
                        TAG,
                        "SQLite integrity check failed: header claims $pageCount pages ($expectedSize bytes), file is $actualSize bytes"
                    )
                }
                valid
            }
        } catch (e: Exception) {
            Log.w(TAG, "SQLite integrity check error for $filePath", e)
            false
        }
    }

    private fun openDatabaseFromDirectPath(): SQLiteDatabase {
        Log.d(TAG, "Opening database from direct path: $directPath")
        if (directPath != null) {
            val file = File(directPath)
            if (file.exists() && !checkSqliteIntegrity(directPath)) {
                Log.e(TAG, "Cached database corrupted at $directPath, removing and falling back")
                file.delete()
                val metadataFile = File(file.parentFile, "${file.name}.metadata")
                if (metadataFile.exists()) metadataFile.delete()
                corruptionDetected = true
                throw IllegalStateException("Database corrupted at $directPath")
            }
        }
        return try {
            val db = SQLiteDatabase.openDatabase(
                directPath!!,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                SafeDatabaseErrorHandler()
            )
            Log.d(TAG, "Database opened successfully from direct path")
            DatabaseOptimizer.optimizeDatabase(db)
            hasQuadkey = DatabaseTypeUtils.hasColumn(db, "geo", "quadkey")
            indexLevel = DatabaseIndices.determineIndexLevel(db)
            Log.d(TAG, "hasQuadkey=${this.hasQuadkey}, indexLevel=${this.indexLevel}")
            db
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open database using direct path: ${e.message}", e)
            throw e
        }
    }

    private fun isOriginalFileChanged(uri: Uri, cachedFile: File): Boolean {
        val originalLastModified =
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(cursor.getColumnIndexOrThrow("last_modified"))
            } ?: return true

        val cachedLastModified = getCachedLastModified(cachedFile)
        return originalLastModified != cachedLastModified
    }

    private fun openDatabaseFromUri(): SQLiteDatabase {
        Log.d(TAG, "Opening database from URI: ${dbUri.path}")
        val cachedFile = getCachedFile(dbUri)
        return if (cachedFile != null && !isOriginalFileChanged(dbUri, cachedFile)) {
            try {
                val db = SQLiteDatabase.openDatabase(
                    cachedFile.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    SafeDatabaseErrorHandler()
                )
                hasQuadkey = DatabaseTypeUtils.hasColumn(db, "geo", "quadkey")
                db
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open cached file, creating new one", e)
                cachedFile.delete()
                openDatabaseFromTempFile()
            }
        } else {
            openDatabaseFromTempFile()
        }
    }

    private fun openDatabaseFromTempFile(): SQLiteDatabase {
        val tempFile = copyUriToTempFileWithRetry(dbUri)
        val db = SQLiteDatabase.openDatabase(
            tempFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            SafeDatabaseErrorHandler()
        )
        hasQuadkey = DatabaseTypeUtils.hasColumn(db, "geo", "quadkey")
        indexLevel = DatabaseIndices.determineIndexLevel(db)
        Log.d(TAG, "indexLevel=${this.indexLevel}")
        return db
    }

    fun getSelectedFileSize(): Float {
        return selectedFileSize
    }

    fun getCachedDbPath(): String? {
        return database?.path
    }

    private fun getCachedLastModified(cachedFile: File): Long {
        val metadataFile = File(cachedFile.parentFile, "${cachedFile.name}.metadata")
        return if (metadataFile.exists()) {
            metadataFile.readText().toLongOrNull() ?: 0
        } else {
            0
        }
    }

    suspend fun loadNetworkInfo(bssidDecimal: Long): Map<String, Any?>? =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting loadNetworkInfo for decimal BSSID: $bssidDecimal")

            if (CompatibilityHelper.isLowMemoryDevice()) {
                val runtime = Runtime.getRuntime()
                val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                val maxMemory = runtime.maxMemory()

                if (usedMemory > maxMemory * 0.8) {
                    Log.w(TAG, "Low memory, clearing cache before network info load")
                    clearCache()
                }
            }

            databaseLock.withLock {
                try {
                    val tableName = DatabaseTypeUtils.getMainTableName(database!!)
                    val hasGeo = DatabaseTypeUtils.hasColumn(database!!, "geo", "latitude")
                    val hasComments = DatabaseTypeUtils.hasColumn(database!!, "comments", "comment")
                    val hasGeoSource = DatabaseTypeUtils.hasColumn(database!!, "geo", "source")
                    val hasGeoTime = DatabaseTypeUtils.hasColumn(database!!, "geo", "time")
                    val geoCols = buildString {
                        append("g.latitude, g.longitude")
                        if (hasGeoSource) append(", g.source as geo_source")
                        if (hasGeoTime) append(", g.time as geo_time")
                    }

                    var query = if (hasGeo && hasComments) {
                        "SELECT n.*, $geoCols, c.comment FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID LEFT JOIN comments c ON n.cmtid = c.cmtid WHERE n.BSSID = ?"
                    } else if (hasGeo) {
                        "SELECT n.*, $geoCols FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.BSSID = ?"
                    } else if (hasComments) {
                        "SELECT n.*, c.comment FROM $tableName n LEFT JOIN comments c ON n.cmtid = c.cmtid WHERE n.BSSID = ?"
                    } else {
                        "SELECT n.* FROM $tableName n WHERE n.BSSID = ?"
                    }
                    var args = arrayOf(bssidDecimal.toString())

                    if (hasQuadkey) {
                        val unionQuery = if (hasGeo && hasComments) {
                            "SELECT n.*, $geoCols, c.comment FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID LEFT JOIN comments c ON n.cmtid = c.cmtid WHERE n.NoBssid = 1 AND n.BSSID IS NULL LIMIT 1"
                        } else if (hasGeo) {
                            "SELECT n.*, $geoCols FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.NoBssid = 1 AND n.BSSID IS NULL LIMIT 1"
                        } else if (hasComments) {
                            "SELECT n.*, c.comment FROM $tableName n LEFT JOIN comments c ON n.cmtid = c.cmtid WHERE n.NoBssid = 1 AND n.BSSID IS NULL LIMIT 1"
                        } else {
                            "SELECT n.* FROM $tableName n WHERE n.NoBssid = 1 AND n.BSSID IS NULL LIMIT 1"
                        }
                        query += " UNION $unionQuery"
                    }

                    database?.rawQuery(query, args)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val result = buildMap {
                                for (i in 0 until cursor.columnCount) {
                                    val columnName = cursor.getColumnName(i)
                                    put(
                                        columnName, when (cursor.getType(i)) {
                                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                            Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(i)
                                            else -> cursor.getString(i)
                                        }
                                    )
                                }
                            }.toMutableMap()

                            Log.d(TAG, "Retrieved network info with ${result.size} fields")
                            result
                        } else {
                            Log.d(TAG, "No network info found for BSSID: $bssidDecimal")
                            null
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading network info for BSSID: $bssidDecimal", e)
                    null
                } finally {
                    Log.d(TAG, "Releasing database lock for BSSID: $bssidDecimal")
                }
            }
        }

    suspend fun loadAllNetworkInfo(bssidDecimal: Long): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting loadAllNetworkInfo for decimal BSSID: $bssidDecimal")

            val allRecords = mutableListOf<Map<String, Any?>>()

            databaseLock.withLock {
                try {
                    val tableName = DatabaseTypeUtils.getMainTableName(database!!)
                    val hasGeo = DatabaseTypeUtils.hasColumn(database!!, "geo", "latitude")
                    val hasComments = DatabaseTypeUtils.hasColumn(database!!, "comments", "comment")
                    val hasGeoSource = DatabaseTypeUtils.hasColumn(database!!, "geo", "source")
                    val hasGeoTime = DatabaseTypeUtils.hasColumn(database!!, "geo", "time")
                    val geoCols = buildString {
                        append("g.latitude, g.longitude")
                        if (hasGeoSource) append(", g.source as geo_source")
                        if (hasGeoTime) append(", g.time as geo_time")
                    }

                    val query = if (hasGeo && hasComments) {
                        "SELECT n.*, $geoCols, c.comment FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID LEFT JOIN comments c ON n.cmtid = c.cmtid WHERE n.BSSID = ?"
                    } else if (hasGeo) {
                        "SELECT n.*, $geoCols FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.BSSID = ?"
                    } else if (hasComments) {
                        "SELECT n.*, c.comment FROM $tableName n LEFT JOIN comments c ON n.cmtid = c.cmtid WHERE n.BSSID = ?"
                    } else {
                        "SELECT n.* FROM $tableName n WHERE n.BSSID = ?"
                    }
                    val args = arrayOf(bssidDecimal.toString())

                    database?.rawQuery(query, args)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            do {
                                val result = buildMap {
                                    for (i in 0 until cursor.columnCount) {
                                        val columnName = cursor.getColumnName(i)
                                        put(
                                            columnName, when (cursor.getType(i)) {
                                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                                Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(i)
                                                else -> cursor.getString(i)
                                            }
                                        )
                                    }
                                }.toMutableMap()

                                allRecords.add(result)
                            } while (cursor.moveToNext())
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading all network info for BSSID: $bssidDecimal", e)
                }
            }

            Log.d(TAG, "Retrieved ${allRecords.size} records for BSSID: $bssidDecimal")
            allRecords
        }

    suspend fun getPointsInBoundingBox(
        bounds: BoundingBox,
        limit: Int = Int.MAX_VALUE
    ): List<Triple<Long, Double, Double>> {
        val queryStart = System.currentTimeMillis()
        Log.d(TAG, "========== getPointsInBoundingBox START ==========")
        Log.d(
            TAG,
            "Bounds: lat(${bounds.latSouth}-${bounds.latNorth}), lon(${bounds.lonWest}-${bounds.lonEast})"
        )
        Log.d(TAG, "hasQuadkey=$hasQuadkey, limit=$limit")

        return withContext(Dispatchers.IO) {
            val effectiveLimit = minOf(limit, PerformanceManager.MAX_POINTS_PER_QUERY)
            val initialCapacity = when {
                effectiveLimit > 50000 -> 50000
                effectiveLimit > 10000 -> 10000
                else -> effectiveLimit
            }
            val points = ArrayList<Triple<Long, Double, Double>>(initialCapacity)

            try {
                if (database == null) {
                    Log.e(TAG, "Database is null - cannot execute query")
                    return@withContext emptyList()
                }
                databaseLock.withLock {

                    var cursor: Cursor? = null

                    if (hasQuadkey) {
                        Log.d(TAG, "[Quadkey] Using quadkey-based query")
                        val zoomLevel = 23
                        val qkMinStr =
                            latLonToQuadkeyString(bounds.latNorth, bounds.lonWest, zoomLevel)
                        val qkMaxStr =
                            latLonToQuadkeyString(bounds.latSouth, bounds.lonEast, zoomLevel)
                        Log.d(TAG, "[Quadkey] Min quadkey: $qkMinStr, Max quadkey: $qkMaxStr")

                        val commonPrefix = findCommonPrefix(qkMinStr, qkMaxStr)
                        Log.d(
                            TAG,
                            "[Quadkey] Common prefix length: ${commonPrefix.length}, prefix: '$commonPrefix'"
                        )

                        if (commonPrefix.isNotEmpty() && commonPrefix.length >= 2) {
                            val query =
                                DatabaseIndices.getGeoQueryWithPrefix(commonPrefix, zoomLevel)
                            Log.d(
                                TAG,
                                "[Quadkey] Using BETWEEN prefix query with prefix='$commonPrefix' at zoom=$zoomLevel"
                            )
                            cursor = database?.rawQuery(query, emptyArray())
                        } else {
                            var qkMinStr =
                                latLonToQuadkeyString(bounds.latNorth, bounds.lonWest, zoomLevel)
                            var qkMaxStr =
                                latLonToQuadkeyString(bounds.latSouth, bounds.lonEast, zoomLevel)
                            if (qkMinStr.length < zoomLevel) qkMinStr =
                                qkMinStr + "0".repeat(zoomLevel - qkMinStr.length)
                            if (qkMaxStr.length < zoomLevel) qkMaxStr =
                                qkMaxStr + "3".repeat(zoomLevel - qkMaxStr.length)
                            val decimalMin = java.lang.Long.parseLong(qkMinStr, 4)
                            val decimalMax = java.lang.Long.parseLong(qkMaxStr, 4)
                            val finalMin = minOf(decimalMin, decimalMax)
                            val finalMax = maxOf(decimalMin, decimalMax)
                            val query = DatabaseIndices.getOptimalGeoQuery(true)
                            Log.d(
                                TAG,
                                "[Quadkey] Fallback to BETWEEN query: min=$finalMin, max=$finalMax (from base-4 strings)"
                            )
                            cursor = database?.rawQuery(
                                query,
                                arrayOf(finalMin.toString(), finalMax.toString())
                            )
                        }
                    } else {
                        Log.d(TAG, "[LatLon] Using lat/lon bounds query")
                        val query = DatabaseIndices.getOptimalGeoQuery(false)
                        cursor = database?.rawQuery(
                            query, arrayOf(
                                bounds.latSouth.toString(),
                                bounds.latNorth.toString(),
                                bounds.lonWest.toString(),
                                bounds.lonEast.toString()
                            )
                        )
                    }

                    cursor?.use { cur ->
                        Log.d(TAG, "[Query] Executing cursor...")
                        if (cur.moveToFirst()) {
                            val bssidIndex = cur.getColumnIndex("BSSID")
                            val latIndex = cur.getColumnIndex("latitude")
                            val lonIndex = cur.getColumnIndex("longitude")

                            Log.d(
                                TAG,
                                "[Query] Column indices - BSSID:$bssidIndex, latitude:$latIndex, longitude:$lonIndex"
                            )

                            var processedCount = 0
                            do {
                                if (effectiveLimit != Int.MAX_VALUE && points.size >= effectiveLimit) {
                                    Log.w(TAG, "Reached effective points limit: $effectiveLimit")
                                    break
                                }

                                if (bssidIndex < 0 || latIndex < 0 || lonIndex < 0) {
                                    Log.e(TAG, "[Query] Invalid column index detected!")
                                    break
                                }

                                val bssid = cur.getLong(bssidIndex)
                                val lat = cur.getDouble(latIndex)
                                val lon = cur.getDouble(lonIndex)
                                points.add(Triple(bssid, lat, lon))

                                processedCount++
                                if (processedCount % 5000 == 0) {
                                    yield()
                                }
                            } while (cur.moveToNext())
                        } else {
                            Log.w(TAG, "[Query] Cursor is empty - no rows returned")
                        }
                        Log.d(
                            TAG,
                            "[Query] Retrieved ${points.size} points from database in ${System.currentTimeMillis() - queryStart}ms"
                        )
                    } ?: run {
                        Log.e(TAG, "[Query] Cursor is null!")
                    }
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OutOfMemoryError getting points in bounding box", e)
                points.clear()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error getting points in bounding box", e)
            }

            val totalTime = System.currentTimeMillis() - queryStart
            Log.d(
                TAG,
                "========== getPointsInBoundingBox END: ${points.size} points in ${totalTime}ms =========="
            )
            points
        }
    }

    suspend fun getClusteredPointsInBoundingBox(
        bounds: BoundingBox,
        zoom: Double,
        limit: Int = Int.MAX_VALUE,
        scatterMode: Boolean = false
    ): List<ClusteredMapPoint> = withContext(Dispatchers.IO) {
        val queryStart = System.currentTimeMillis()
        val effectiveLimit = minOf(limit, PerformanceManager.MAX_POINTS_PER_QUERY)
        Log.d(TAG, "========== getClusteredPointsInBoundingBox START ==========")
        Log.d(
            TAG,
            "Bounds: lat(${bounds.latSouth}-${bounds.latNorth}), lon(${bounds.lonWest}-${bounds.lonEast}), zoom=$zoom, limit=$effectiveLimit"
        )

        if (!hasQuadkey || database == null) {
            Log.d(TAG, "[No Quadkey] Falling back to standard bounding box query")
            return@withContext getPointsInBoundingBox(
                bounds,
                effectiveLimit
            ).map { (bssid, lat, lon) ->
                ClusteredMapPoint(bssid, lat, lon, 1, false)
            }
        }

        try {
            if (database == null) {
                Log.e(TAG, "Database is null")
                return@withContext emptyList()
            }
            databaseLock.withLock {
                val db = database!!
                val maxZoom = 23.0
                val isHighZoom = zoom >= maxZoom - 1
                val effectiveScatterMode = scatterMode || isHighZoom
                val groupLevel = if (effectiveScatterMode) maxZoom else zoom + 2
                val mask = (2 * (maxZoom.toInt() - groupLevel.toInt())).coerceAtLeast(0)

                Log.d(
                    TAG,
                    "[Cluster] zoom=$zoom, groupLevel=$groupLevel, mask=$mask, scatter=$effectiveScatterMode"
                )

                val zoomInt = 23
                val qkMin = QuadkeyUtils.latLonToQuadkey(bounds.latNorth, bounds.lonWest, zoomInt)
                val qkMax = QuadkeyUtils.latLonToQuadkey(bounds.latSouth, bounds.lonEast, zoomInt)
                val qkLower = minOf(qkMin, qkMax)
                val qkUpper = maxOf(qkMin, qkMax)

                Log.d(TAG, "[Cluster] quadkey range: $qkLower .. $qkUpper")

                val needLatLonFilter = zoom < 14
                val points = ArrayList<ClusteredMapPoint>()

                if (effectiveScatterMode) {
                    val scatterLimit = getZoomBasedLimit(zoom)
                    val latLonClause =
                        if (needLatLonFilter) " AND latitude >= ? AND latitude <= ? AND longitude >= ? AND longitude <= ?" else ""
                    val query =
                        "SELECT BSSID, latitude, longitude FROM geo WHERE quadkey >= ? AND quadkey <= ?$latLonClause LIMIT ?"
                    val args = if (needLatLonFilter) arrayOf(
                        qkLower.toString(),
                        qkUpper.toString(),
                        bounds.latSouth.toString(),
                        bounds.latNorth.toString(),
                        bounds.lonWest.toString(),
                        bounds.lonEast.toString(),
                        scatterLimit.toString()
                    ) else arrayOf(qkLower.toString(), qkUpper.toString(), scatterLimit.toString())
                    db.rawQuery(query, args)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val bssidIdx = cursor.getColumnIndex("BSSID")
                            val latIdx = cursor.getColumnIndex("latitude")
                            val lonIdx = cursor.getColumnIndex("longitude")

                            if (bssidIdx >= 0 && latIdx >= 0 && lonIdx >= 0) {
                                do {
                                    val bssid = cursor.getLong(bssidIdx)
                                    val lat = cursor.getDouble(latIdx)
                                    val lon = cursor.getDouble(lonIdx)
                                    points.add(ClusteredMapPoint(bssid, lat, lon, 1, false))

                                    if (points.size % 5000 == 0) {
                                        yield()
                                    }
                                } while (cursor.moveToNext())
                            }
                        }
                    }
                } else {
                    val divisor = 1L shl mask
                    val clusterLimit = getZoomBasedLimit(zoom)
                    val latLonClause =
                        if (needLatLonFilter) " AND latitude >= ? AND latitude <= ? AND longitude >= ? AND longitude <= ?" else ""
                    val query =
                        "SELECT MIN(BSSID) as BSSID, AVG(latitude) as avg_lat, AVG(longitude) as avg_lon, COUNT(*) as count FROM geo WHERE quadkey >= ? AND quadkey <= ?$latLonClause GROUP BY (quadkey / $divisor) LIMIT $clusterLimit"
                    val args = if (needLatLonFilter) arrayOf(
                        qkLower.toString(),
                        qkUpper.toString(),
                        bounds.latSouth.toString(),
                        bounds.latNorth.toString(),
                        bounds.lonWest.toString(),
                        bounds.lonEast.toString()
                    ) else arrayOf(qkLower.toString(), qkUpper.toString())
                    db.rawQuery(query, args)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val bssidIdx = cursor.getColumnIndex("BSSID")
                            val latIdx = cursor.getColumnIndex("avg_lat")
                            val lonIdx = cursor.getColumnIndex("avg_lon")
                            val countIdx = cursor.getColumnIndex("count")

                            if (bssidIdx >= 0 && latIdx >= 0 && lonIdx >= 0 && countIdx >= 0) {
                                do {
                                    val bssid = cursor.getLong(bssidIdx)
                                    val lat = cursor.getDouble(latIdx)
                                    val lon = cursor.getDouble(lonIdx)
                                    val count = cursor.getInt(countIdx)
                                    points.add(ClusteredMapPoint(bssid, lat, lon, count, count > 1))

                                    if (points.size % 5000 == 0) {
                                        yield()
                                    }
                                } while (cursor.moveToNext())
                            }
                        }
                    }
                }

                Log.d(
                    TAG,
                    "[Cluster] Retrieved ${points.size} clustered points in ${System.currentTimeMillis() - queryStart}ms"
                )
                points
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError in clustered query", e)
            emptyList()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error in clustered query, falling back to standard query", e)
            getPointsInBoundingBoxFallback(bounds)
        }
    }

    suspend fun getClusteredPointsByTileRange(
        tileX1: Int,
        tileY1: Int,
        tileX2: Int,
        tileY2: Int,
        zoom: Int,
        scatterMode: Boolean = false
    ): List<ClusteredMapPoint> = withContext(Dispatchers.IO) {
        if (!hasQuadkey || database == null) {
            Log.d(TAG, "[TileQuery-NoQuadkey] Falling back to lat/lon bounds")
            val bounds = QuadkeyUtils.getTileRangeBounds(
                TileRange(tileX1, tileY1, tileX2, tileY2),
                zoom
            )
            return@withContext getPointsInBoundingBox(
                bounds,
                Int.MAX_VALUE
            ).map { (bssid, lat, lon) ->
                ClusteredMapPoint(bssid, lat, lon, 1, false)
            }
        }

        try {
            databaseLock.withLock {
                val db = database ?: return@withLock emptyList()

                val maxZoom = 23.0
                val isHighZoom = zoom >= maxZoom - 1
                val effectiveScatterMode = scatterMode || isHighZoom
                val groupLevel = if (effectiveScatterMode) maxZoom else zoom + 2
                val mask = (2 * (maxZoom.toInt() - groupLevel.toInt())).coerceAtLeast(0)

                Log.d(
                    TAG,
                    "[TileQuery] tileRange=($tileX1,$tileY1)-($tileX2,$tileY2), zoom=$zoom, groupLevel=$groupLevel, mask=$mask"
                )

                val dbZoom = 23
                val latNorth = QuadkeyUtils.tileXYToLat(tileY1, zoom)
                val latSouth = QuadkeyUtils.tileXYToLat(tileY2 + 1, zoom)
                val lonWest = QuadkeyUtils.tileXYToLon(tileX1, zoom)
                val lonEast = QuadkeyUtils.tileXYToLon(tileX2 + 1, zoom)

                val qkMin = QuadkeyUtils.latLonToQuadkey(latNorth, lonWest, dbZoom)
                val qkMax = QuadkeyUtils.latLonToQuadkey(latSouth, lonEast, dbZoom)
                val qkLower = minOf(qkMin, qkMax)
                val qkUpper = maxOf(qkMin, qkMax)

                Log.d(TAG, "[TileQuery] quadkey range: $qkLower .. $qkUpper")

                val needLatLonFilter = zoom < 14
                val points = ArrayList<ClusteredMapPoint>()

                if (effectiveScatterMode) {
                    val scatterLimit = getZoomBasedLimit(zoom.toDouble())
                    val latLonClause =
                        if (needLatLonFilter) " AND latitude >= ? AND latitude <= ? AND longitude >= ? AND longitude <= ?" else ""
                    val query =
                        "SELECT BSSID, latitude, longitude FROM geo WHERE quadkey >= ? AND quadkey <= ?$latLonClause LIMIT ?"
                    val args = if (needLatLonFilter) arrayOf(
                        qkLower.toString(),
                        qkUpper.toString(),
                        latSouth.toString(),
                        latNorth.toString(),
                        lonWest.toString(),
                        lonEast.toString(),
                        scatterLimit.toString()
                    ) else arrayOf(qkLower.toString(), qkUpper.toString(), scatterLimit.toString())
                    db.rawQuery(query, args)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val bssidIdx = cursor.getColumnIndex("BSSID")
                            val latIdx = cursor.getColumnIndex("latitude")
                            val lonIdx = cursor.getColumnIndex("longitude")

                            if (bssidIdx >= 0 && latIdx >= 0 && lonIdx >= 0) {
                                do {
                                    val bssid = cursor.getLong(bssidIdx)
                                    val lat = cursor.getDouble(latIdx)
                                    val lon = cursor.getDouble(lonIdx)
                                    points.add(ClusteredMapPoint(bssid, lat, lon, 1, false))

                                    if (points.size % 5000 == 0) {
                                        yield()
                                    }
                                } while (cursor.moveToNext())
                            }
                        }
                    }
                } else {
                    val divisor = 1L shl mask
                    val clusterLimit = getZoomBasedLimit(zoom.toDouble())
                    val latLonClause =
                        if (needLatLonFilter) " AND latitude >= ? AND latitude <= ? AND longitude >= ? AND longitude <= ?" else ""
                    val query =
                        "SELECT MIN(BSSID) as BSSID, AVG(latitude) as avg_lat, AVG(longitude) as avg_lon, COUNT(*) as count FROM geo WHERE quadkey >= ? AND quadkey <= ?$latLonClause GROUP BY (quadkey / $divisor) LIMIT $clusterLimit"
                    val args = if (needLatLonFilter) arrayOf(
                        qkLower.toString(),
                        qkUpper.toString(),
                        latSouth.toString(),
                        latNorth.toString(),
                        lonWest.toString(),
                        lonEast.toString()
                    ) else arrayOf(qkLower.toString(), qkUpper.toString())
                    db.rawQuery(query, args)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val bssidIdx = cursor.getColumnIndex("BSSID")
                            val latIdx = cursor.getColumnIndex("avg_lat")
                            val lonIdx = cursor.getColumnIndex("avg_lon")
                            val countIdx = cursor.getColumnIndex("count")

                            if (bssidIdx >= 0 && latIdx >= 0 && lonIdx >= 0 && countIdx >= 0) {
                                do {
                                    val bssid = cursor.getLong(bssidIdx)
                                    val lat = cursor.getDouble(latIdx)
                                    val lon = cursor.getDouble(lonIdx)
                                    val count = cursor.getInt(countIdx)
                                    points.add(ClusteredMapPoint(bssid, lat, lon, count, count > 1))

                                    if (points.size % 5000 == 0) {
                                        yield()
                                    }
                                } while (cursor.moveToNext())
                            }
                        }
                    }
                }

                Log.d(TAG, "[TileQuery] Retrieved ${points.size} clustered points")
                points
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError in tile query", e)
            emptyList()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error in tile query, falling back to standard query", e)
            val bounds = QuadkeyUtils.getTileRangeBounds(
                TileRange(tileX1, tileY1, tileX2, tileY2),
                zoom
            )
            getPointsInBoundingBoxFallback(bounds)
        }
    }

    private data class ClusterAccumulator(
        var bssid: Long = 0L,
        var lat: Double = 0.0,
        var lon: Double = 0.0,
        var sumLat: Double = 0.0,
        var sumLon: Double = 0.0,
        var count: Int = 0
    )

    private fun getZoomBasedLimit(zoom: Double): Int {
        return when {
            zoom < 10 -> 2000
            zoom < 11 -> 4000
            zoom < 12 -> 8000
            zoom < 13 -> 15000
            zoom < 14 -> 25000
            zoom < 15 -> 40000
            zoom < 16 -> 60000
            zoom < 18 -> 80000
            else -> PerformanceManager.MAX_POINTS_PER_QUERY
        }
    }

    private suspend fun getPointsInBoundingBoxFallback(bounds: BoundingBox): List<ClusteredMapPoint> {
        return getPointsInBoundingBox(
            bounds,
            PerformanceManager.MAX_POINTS_PER_QUERY
        ).map { (bssid, lat, lon) ->
            ClusteredMapPoint(bssid, lat, lon, 1, false)
        }
    }

    private fun findCommonPrefix(str1: String, str2: String): String {
        var i = 0
        while (i < str1.length && i < str2.length) {
            if (str1[i] == str2[i]) {
                i++
            } else {
                break
            }
        }
        return str1.substring(0, i)
    }

    private fun latLonToQuadkeyString(lat: Double, lon: Double, zoom: Int): String {
        val qk = QuadkeyUtils.latLonToQuadkey(lat, lon, zoom)
        var n = qk
        val sb = StringBuilder()
        for (i in 0 until zoom) {
            sb.append(n % 4)
            n /= 4
        }
        return sb.reverse().toString()
    }

    private fun lonToTileX(longitude: Double, zoom: Int): Int {
        val clippedLon = longitude.coerceIn(-180.0, 180.0)
        val x = (clippedLon + 180.0) / 360.0
        val sizeInTiles = 1 shl zoom
        return kotlin.math.min((x * sizeInTiles).toInt(), sizeInTiles - 1)
    }

    private fun latToTileY(latitude: Double, zoom: Int): Int {
        val clippedLat = latitude.coerceIn(-85.05112878, 85.05112878)
        val sinLat = Math.sin(Math.toRadians(clippedLat))
        val e = 0.0818191908426

        val y = 0.5 - (atanh(sinLat) - e * atanh(e * sinLat)) / (2.0 * Math.PI)
        val sizeInTiles = 1 shl zoom

        return kotlin.math.min((y * sizeInTiles).toInt(), sizeInTiles - 1)
    }

    private fun atanh(x: Double): Double {
        return 0.5 * Math.log((1 + x) / (1 - x))
    }


    private fun saveCachedLastModified(cachedFile: File, lastModified: Long) {
        val metadataFile = File(cachedFile.parentFile, "${cachedFile.name}.metadata")
        metadataFile.writeText(lastModified.toString())
    }

    private fun getCachedFile(uri: Uri): File? {
        val fileName = getFileNameFromUri(uri)
        val cachedFile = File(cacheDir, fileName)
        return if (cachedFile.exists()) cachedFile else null
    }

    private fun copyUriToTempFileWithRetry(uri: Uri, maxRetries: Int = 3): File {
        var lastException: Exception? = null
        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        val effectiveRetries = if (isMainThread) 1 else maxRetries

        if (isMainThread) {
            Log.w(
                TAG,
                "copyUriToTempFileWithRetry called on main thread — suppressing retries to avoid blocking UI"
            )
        }

        repeat(effectiveRetries) { attempt ->
            try {
                val fileName = getFileNameFromUri(uri)
                val tempFile = File(cacheDir, fileName)

                if (tempFile.exists()) {
                    if (CompatibilityHelper.isFileAccessible(tempFile)) {
                        val lastModified = getCachedLastModified(tempFile)
                        val originalLastModified = getOriginalLastModified(uri)
                        if (lastModified == originalLastModified) {
                            if (checkSqliteIntegrity(tempFile.absolutePath)) {
                                selectedFileSize = tempFile.length().toFloat() / (1024 * 1024)
                                return tempFile
                            } else {
                                Log.w(TAG, "Cached file is corrupted, re-copying: ${tempFile.path}")
                            }
                        }
                    }
                    tempFile.delete()
                }

                val bufferSize = if (CompatibilityHelper.isLowMemoryDevice()) 4096 else 8192
                var totalBytes = 0L

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(bufferSize)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead

                            if (totalBytes > 500 * 1024 * 1024 && CompatibilityHelper.isLowMemoryDevice()) {
                                throw IllegalStateException("File too large for this device")
                            }
                        }
                    }
                }

                if (!CompatibilityHelper.isFileAccessible(tempFile)) {
                    tempFile.delete()
                    throw IllegalStateException("Copied file is not accessible")
                }

                if (!checkSqliteIntegrity(tempFile.absolutePath)) {
                    tempFile.delete()
                    throw IllegalStateException("Copied file from URI is corrupted/incomplete")
                }

                val originalLastModified = getOriginalLastModified(uri)
                saveCachedLastModified(tempFile, originalLastModified)
                selectedFileSize = tempFile.length().toFloat() / (1024 * 1024)

                return tempFile

            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Copy attempt ${attempt + 1} failed", e)
                if (!isMainThread && attempt < effectiveRetries - 1) {
                    Thread.sleep(1000)
                }
            }
        }

        throw IllegalArgumentException(
            "Failed to copy URI to temp file after $effectiveRetries attempts",
            lastException
        )
    }

    private fun getOriginalLastModified(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val lastModifiedIndex = cursor.getColumnIndex("last_modified")
                    if (lastModifiedIndex != -1) {
                        cursor.getLong(lastModifiedIndex)
                    } else {
                        System.currentTimeMillis()
                    }
                } else {
                    System.currentTimeMillis()
                }
            } ?: System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get original last modified time", e)
            System.currentTimeMillis()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        val name = uri.lastPathSegment?.split("/")?.last() ?: "database"
        return if (name.endsWith(".sqlite", ignoreCase = true)) name else "$name.sqlite"
    }

    override fun onCreate(db: SQLiteDatabase?) {
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
    }

    fun getTableNames(): List<String> {
        return database?.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
            ?.use { cursor ->
                val tableNames = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    tableNames.add(cursor.getString(0))
                }
                tableNames
            } ?: emptyList()
    }

    suspend fun searchNetworksByBSSIDsAsync(bssids: List<String>): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            try {
                if (bssids.isEmpty()) return@withContext emptyList()

                val maxBssids = when {
                    bssids.size <= 100 -> bssids.size
                    CompatibilityHelper.isLowMemoryDevice() -> 50
                    else -> 100
                }
                val chunkedBssids = bssids.chunked(maxBssids)

                val decimalBSSIDs = mutableMapOf<String, Long>()

                bssids.forEach { bssid ->
                    val decimal = convertMacToDecimal(bssid)
                    if (decimal != -1L) {
                        decimalBSSIDs[bssid] = decimal
                    }
                }

                if (decimalBSSIDs.isEmpty()) return@withContext emptyList()

                val tableName = DatabaseTypeUtils.getMainTableName(database!!)

                chunkedBssids.flatMap { chunk ->
                    val chunkDecimals = chunk.mapNotNull { bssid -> decimalBSSIDs[bssid] }
                    if (chunkDecimals.isEmpty()) return@flatMap emptyList()

                    val placeholders = chunkDecimals.joinToString(",") { "?" }

                    val (searchCols, _) = getValidSearchColumns(tableName)
                    var query =
                        "SELECT $searchCols, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.BSSID IN ($placeholders)"
                    if (hasQuadkey) {
                        query += " UNION SELECT $searchCols, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.NoBssid = 1 AND n.BSSID IS NULL LIMIT ${chunk.size}"
                    }

                    Log.d(TAG, "Using query: $query")
                    database?.rawQuery(query, chunkDecimals.map { it.toString() }.toTypedArray())
                        ?.use { cursor ->
                            buildList {
                                var processedCount = 0
                                while (cursor.moveToNext()) {
                                    val result = mutableMapOf<String, Any?>()
                                    for (i in 0 until cursor.columnCount) {
                                        val columnName = cursor.getColumnName(i)
                                        result[columnName] = when (cursor.getType(i)) {
                                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                            Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(i)
                                            else -> cursor.getString(i)
                                        }
                                    }

                                    if (result["BSSID"] is Long) {
                                        val bssid = decimalToMac(result["BSSID"] as Long)
                                        result["BSSID"] = bssid
                                        resultsCache[bssid] = result
                                    }

                                    add(result)

                                    processedCount++
                                    if (processedCount % 1000 == 0) {
                                        yield()
                                    }
                                }
                            }
                        } ?: emptyList()
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OutOfMemoryError in searchNetworksByBSSIDsAsync", e)
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error in searchNetworksByBSSIDsAsync: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun searchNetworksByBSSIDDecimals(
        bssidDecimals: List<Long>,
        includeGeo: Boolean = false
    ): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            try {
                if (bssidDecimals.isEmpty()) return@withContext emptyList()

                val maxBssids = when {
                    bssidDecimals.size <= 100 -> bssidDecimals.size
                    CompatibilityHelper.isLowMemoryDevice() -> 50
                    else -> 100
                }
                val chunkedDecimals = bssidDecimals.chunked(maxBssids)

                val tableName = DatabaseTypeUtils.getMainTableName(database!!)

                chunkedDecimals.flatMap { chunk ->
                    if (chunk.isEmpty()) return@flatMap emptyList()

                    val placeholders = chunk.joinToString(",") { "?" }
                    val (searchCols, _) = getValidSearchColumns(tableName)
                    var query =
                        "SELECT $searchCols FROM $tableName n WHERE n.BSSID IN ($placeholders)"

                    if (includeGeo) {
                        query =
                            "SELECT inner_query.*, g.latitude, g.longitude FROM (SELECT $searchCols FROM $tableName n WHERE n.BSSID IN ($placeholders)) inner_query LEFT JOIN geo g ON inner_query.BSSID = g.BSSID"
                        if (hasQuadkey) {
                            query += " UNION SELECT inner_query.*, g.latitude, g.longitude FROM (SELECT $searchCols FROM $tableName n WHERE n.NoBssid = 1 AND n.BSSID IS NULL LIMIT ${chunk.size}) inner_query LEFT JOIN geo g ON inner_query.BSSID = g.BSSID"
                        }
                    } else if (hasQuadkey) {
                        query += " UNION SELECT $searchCols FROM $tableName n WHERE n.NoBssid = 1 AND n.BSSID IS NULL LIMIT ${chunk.size}"
                    }

                    Log.d(TAG, "Using decimal query: $query")
                    database?.rawQuery(query, chunk.map { it.toString() }.toTypedArray())
                        ?.use { cursor ->
                            buildList {
                                var processedCount = 0
                                while (cursor.moveToNext()) {
                                    val result = mutableMapOf<String, Any?>()
                                    for (i in 0 until cursor.columnCount) {
                                        val columnName = cursor.getColumnName(i)
                                        result[columnName] = when (cursor.getType(i)) {
                                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                            Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(i)
                                            else -> cursor.getString(i)
                                        }
                                    }

                                    if (result["BSSID"] is Long) {
                                        val bssid = decimalToMac(result["BSSID"] as Long)
                                        result["BSSID"] = bssid
                                        resultsCache[bssid] = result
                                    }

                                    add(result)

                                    processedCount++
                                    if (processedCount % 1000 == 0) {
                                        yield()
                                    }
                                }
                            }
                        } ?: emptyList()
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OutOfMemoryError in searchNetworksByBSSIDDecimals", e)
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error in searchNetworksByBSSIDDecimals: ${e.message}", e)
                emptyList()
            }
        }

    private fun convertMacToDecimal(mac: String): Long {
        return try {
            val formats = generateMacFormats(mac)

            for (format in formats) {
                if (format.matches(DECIMAL_REGEX)) {
                    val decimal = format.toLongOrNull()
                    if (decimal != null) return decimal
                }

                val cleanMac = format.replace(HEX_CLEAN_REGEX, "")
                if (cleanMac.length == 12) {
                    val decimal = cleanMac.toLongOrNull(16)
                    if (decimal != null) return decimal
                }
            }

            Log.e(TAG, "Could not convert MAC to decimal: $mac")
            -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error converting MAC to decimal: $mac", e)
            -1L
        }
    }

    suspend fun searchNetworksByESSIDsAsync(essids: List<String>): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            try {
                val validEssids = essids.filter { it.isNotBlank() }
                if (validEssids.isEmpty()) return@withContext emptyList()

                val tableName = DatabaseTypeUtils.getMainTableName(database!!)
                val chunkedEssids = validEssids.chunked(50)
                Log.d(
                    TAG,
                    "Searching for ${validEssids.size} ESSIDs in ${chunkedEssids.size} chunks"
                )

                chunkedEssids.flatMap { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    val (searchCols, _) = getValidSearchColumns(tableName)
                    val query =
                        "SELECT inner_query.*, g.latitude, g.longitude FROM (SELECT $searchCols FROM $tableName n WHERE n.ESSID IN ($placeholders)) inner_query LEFT JOIN geo g ON inner_query.BSSID = g.BSSID"
                    Log.d(TAG, "Using ESSID query: $query")

                    database?.rawQuery(query, chunk.toTypedArray())?.use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val result = mutableMapOf<String, Any?>()
                                for (i in 0 until cursor.columnCount) {
                                    val columnName = cursor.getColumnName(i)
                                    result[columnName] = when (cursor.getType(i)) {
                                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                        Cursor.FIELD_TYPE_FLOAT -> cursor.getFloat(i)
                                        else -> cursor.getString(i)
                                    }
                                }

                                if (result["BSSID"] is Long) {
                                    result["BSSID"] = decimalToMac(result["BSSID"] as Long)
                                }
                                add(result)
                            }
                        }
                    } ?: emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error searching networks by ESSID", e)
                emptyList()
            }
        }

    @Volatile
    private var searchCancelled = false

    fun cancelSearch() {
        searchCancelled = true
    }

    fun searchNetworksByBSSIDAndFieldsPaginated(
        query: String,
        filters: Set<String>,
        searchMode: SearchMode,
        offset: Int,
        limit: Int
    ): List<Map<String, Any?>> {
        searchCancelled = false
        val tableName = DatabaseTypeUtils.getMainTableName(database!!)
        val hexRange = computeBssidHexRange(query)
        val useRange = hexRange != null && hasBssidRangeMatches(tableName, hexRange)
        return runPaginatedSearch(
            tableName, query, filters, searchMode, offset, limit, hexRange, useRange
        )
    }

    private fun computeBssidHexRange(query: String): Pair<Long, Long>? {
        if (query.matches(DECIMAL_REGEX)) return null
        val cleanQuery = query.replace(HEX_CLEAN_REGEX, "")
        if (cleanQuery.isEmpty() || cleanQuery.length > 12) return null
        if (!cleanQuery.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val shift = 4 * (12 - cleanQuery.length)
        val low = cleanQuery.toLong(16) shl shift
        val high = low + (1L shl shift)
        return low to high
    }

    private fun hasBssidRangeMatches(tableName: String, hexRange: Pair<Long, Long>): Boolean {
        return database?.rawQuery(
            "SELECT 1 FROM $tableName n WHERE n.BSSID >= ? AND n.BSSID < ? LIMIT 1",
            arrayOf(hexRange.first.toString(), hexRange.second.toString())
        )?.use { cursor ->
            cursor.count > 0
        } ?: false
    }

    private fun runPaginatedSearch(
        tableName: String,
        query: String,
        filters: Set<String>,
        searchMode: SearchMode,
        offset: Int,
        limit: Int,
        hexRange: Pair<Long, Long>?,
        useRange: Boolean
    ): List<Map<String, Any?>> {
        val branches = mutableListOf<Triple<List<String>, MutableList<String>, Boolean>>()

        fun addBranch(conds: List<String>, params: List<String>, slow: Boolean = false) {
            if (conds.isEmpty()) return
            branches.add(Triple(conds, params.toMutableList(), slow))
        }

        fun combinedBranchConds(): MutableList<String> =
            branches.map { "(${it.first.joinToString(" AND ")})" }.toMutableList()

        fun combinedBranchParams(): MutableList<String> =
            branches.flatMap { it.second }.toMutableList()

        val indexedCols = indexedColumns(tableName)

        filters.forEach { field ->
            when (field) {
                "BSSID" -> {
                    if (isValidBssidQuery(query)) {
                        if (searchMode == SearchMode.EXACT) {
                            val possibleFormats = generateMacFormats(query)
                            var bssidConditionAdded = false
                            val conds = mutableListOf<String>()
                            val params = mutableListOf<String>()

                            possibleFormats.forEach { format ->
                                val decimalValue = macToDecimalSafe(format)
                                if (decimalValue != -1L) {
                                    conds.add("n.BSSID = ?")
                                    params.add(decimalValue.toString())
                                    bssidConditionAdded = true
                                }
                            }

                            if (!bssidConditionAdded) {
                                conds.add("CAST(n.BSSID AS TEXT) = ?")
                                params.add(query)
                            }
                            addBranch(conds, params)
                        } else {
                            when {
                                query.matches(DECIMAL_REGEX) -> {
                                    addBranch(
                                        listOf("CAST(n.BSSID AS TEXT) LIKE ?"),
                                        listOf("%$query%"),
                                        slow = true
                                    )
                                }

                                hexRange != null && useRange -> {
                                    addBranch(
                                        listOf("n.BSSID >= ? AND n.BSSID < ?"),
                                        listOf(
                                            hexRange.first.toString(),
                                            hexRange.second.toString()
                                        )
                                    )
                                }

                                else -> {
                                    val cleanQuery = query.replace(HEX_CLEAN_REGEX, "")
                                    if (cleanQuery.isNotEmpty()) {
                                        val searchPattern = "%${cleanQuery.uppercase()}%"
                                        addBranch(
                                            listOf("UPPER(printf('%012X', n.BSSID)) LIKE ? OR UPPER(substr(printf('%02X:%02X:%02X:%02X:%02X:%02X', (n.BSSID >> 40) & 255, (n.BSSID >> 32) & 255, (n.BSSID >> 24) & 255, (n.BSSID >> 16) & 255, (n.BSSID >> 8) & 255, n.BSSID & 255), 1, length(?)*3-2)) LIKE ?"),
                                            listOf(
                                                searchPattern,
                                                cleanQuery.uppercase(),
                                                searchPattern
                                            ),
                                            slow = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                "ESSID" -> {
                    when (searchMode) {
                        SearchMode.EXACT -> {
                            val (cond, params) = caseInsensitiveEquals("n.ESSID", query)
                            addBranch(listOf(cond), params)
                        }

                        SearchMode.PREFIX -> {
                            if (field in indexedCols) {
                                caseInsensitivePrefix("n.ESSID", query).forEach { (cond, params) ->
                                    addBranch(listOf(cond), params)
                                }
                            } else {
                                addBranch(
                                    listOf("UPPER(n.ESSID) LIKE UPPER(?)"),
                                    listOf("$query%"),
                                    slow = true
                                )
                            }
                        }

                        SearchMode.SUBSTRING -> {
                            val words = query.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                            if (words.size > 1) {
                                addBranch(
                                    words.map { "n.ESSID LIKE ?" },
                                    words.map { "%$it%" },
                                    slow = true
                                )
                            } else {
                                addBranch(
                                    listOf("n.ESSID LIKE ?"),
                                    listOf("%${query}%"),
                                    slow = true
                                )
                            }
                        }
                    }
                }

                "WiFiKey" -> {
                    when (searchMode) {
                        SearchMode.EXACT -> {
                            val (cond, params) = caseInsensitiveEquals("n.WiFiKey", query)
                            addBranch(listOf(cond), params)
                        }

                        SearchMode.PREFIX -> {
                            if (field in indexedCols) {
                                caseInsensitivePrefix(
                                    "n.WiFiKey",
                                    query
                                ).forEach { (cond, params) ->
                                    addBranch(listOf(cond), params)
                                }
                            } else {
                                addBranch(
                                    listOf("UPPER(n.WiFiKey) LIKE UPPER(?)"),
                                    listOf("$query%"),
                                    slow = true
                                )
                            }
                        }

                        SearchMode.SUBSTRING -> {
                            val words = query.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                            if (words.size > 1) {
                                addBranch(
                                    words.map { "n.WiFiKey LIKE ?" },
                                    words.map { "%$it%" },
                                    slow = true
                                )
                            } else {
                                addBranch(
                                    listOf("n.WiFiKey LIKE ?"),
                                    listOf("%${query}%"),
                                    slow = true
                                )
                            }
                        }
                    }
                }

                "WPSPIN" -> {
                    when (searchMode) {
                        SearchMode.EXACT -> {
                            addBranch(listOf("n.WPSPIN = ?"), listOf(query))
                        }

                        SearchMode.PREFIX -> {
                            if (field in indexedCols) {
                                caseInsensitivePrefix("n.WPSPIN", query).forEach { (cond, params) ->
                                    addBranch(listOf(cond), params)
                                }
                            } else {
                                addBranch(
                                    listOf("UPPER(n.WPSPIN) LIKE UPPER(?)"),
                                    listOf("$query%"),
                                    slow = true
                                )
                            }
                        }

                        SearchMode.SUBSTRING -> {
                            addBranch(listOf("n.WPSPIN LIKE ?"), listOf("%${query}%"), slow = true)
                        }
                    }
                }
            }
        }

        if (branches.isEmpty()) return emptyList()

        val (searchCols, outerCols) = getValidSearchColumns(tableName)
        val hasSlowBranch = branches.any { it.third }

        if (branches.size == 1 && !hasSlowBranch) {
            val (conds, params, _) = branches[0]
            val baseQuery =
                "SELECT $outerCols, g.latitude, g.longitude FROM (SELECT $searchCols FROM $tableName n WHERE (${
                    conds.joinToString(" AND ")
                }) ORDER BY n.rowid LIMIT $limit OFFSET $offset) inner_query LEFT JOIN geo g ON inner_query.BSSID = g.BSSID"

            return database?.rawQuery(baseQuery, params.toTypedArray())?.use { cursor ->
                cursor.toSearchResultsRaw()
            } ?: emptyList()
        }

        if (hasSlowBranch) {
            return runChunkedPaginatedSearch(
                tableName,
                combinedBranchConds(),
                combinedBranchParams(),
                offset,
                limit,
                searchCols,
                outerCols
            )
        }

        val unionSql = branches.joinToString(" UNION ALL ") { (conds, _, _) ->
            "SELECT n.rowid, $searchCols FROM $tableName n WHERE ${conds.joinToString(" AND ")}"
        }
        val baseQuery =
            "SELECT $outerCols, g.latitude, g.longitude FROM ($unionSql ORDER BY rowid LIMIT $limit OFFSET $offset) inner_query LEFT JOIN geo g ON inner_query.BSSID = g.BSSID"

        return database?.rawQuery(baseQuery, combinedBranchParams().toTypedArray())?.use { cursor ->
            cursor.toSearchResultsRaw()
        } ?: emptyList()
    }

    private val columnIndexCache = ConcurrentHashMap<String, Set<String>>()

    private fun indexedColumns(tableName: String): Set<String> {
        columnIndexCache[tableName]?.let { return it }
        val cols = mutableSetOf<String>()
        try {
            val indexNames = mutableListOf<String>()
            database?.rawQuery("PRAGMA index_list($tableName)", null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    indexNames.add(cursor.getString(1))
                }
            }
            indexNames.forEach { indexName ->
                database?.rawQuery("PRAGMA index_info($indexName)", null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val colName = cursor.getString(2)
                        if (colName != null) cols.add(colName)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading indexes for $tableName", e)
        }
        columnIndexCache[tableName] = cols
        return cols
    }

    private fun caseVariantParts(
        query: String,
        maxVariants: Int = MAX_PREFIX_VARIANTS
    ): Pair<String, String?> {
        var count = 1
        val prefix = StringBuilder()
        for (ch in query) {
            if (ch.isLetter()) {
                if (count * 2 <= maxVariants) {
                    prefix.append(ch)
                    count *= 2
                } else {
                    return prefix.toString() to query.substring(prefix.length)
                }
            } else {
                prefix.append(ch)
            }
        }
        return prefix.toString() to null
    }

    private fun enumerateCaseVariants(prefix: String): List<String> {
        if (prefix.isEmpty()) return listOf("")
        val alts = prefix.map { ch ->
            if (ch.isLetter()) listOf(ch.lowercaseChar(), ch.uppercaseChar()) else listOf(ch)
        }
        return alts.fold(listOf("")) { acc, options ->
            acc.flatMap { p -> options.map { p + it } }
        }
    }

    private fun caseInsensitivePrefix(
        column: String,
        query: String
    ): List<Pair<String, List<String>>> {
        val (prefix, tail) = caseVariantParts(query)
        return enumerateCaseVariants(prefix).map { variant ->
            val cond = "$column >= ? AND $column < (? || char(0))"
            if (tail == null) {
                cond to listOf(variant, variant)
            } else {
                cond + " AND UPPER(substr($column, ${prefix.length + 1})) LIKE UPPER(?)" to
                        listOf(variant, variant, "$tail%")
            }
        }
    }

    private fun caseInsensitiveEquals(column: String, query: String): Pair<String, List<String>> {
        val (prefix, tail) = caseVariantParts(query)
        val variants = enumerateCaseVariants(prefix)
        val params = variants.toMutableList()
        val cond = "(" + variants.joinToString(" OR ") { "$column = ?" } + ")"
        if (tail != null) {
            params.add(tail)
            return cond + " AND UPPER(substr($column, ${prefix.length + 1})) = UPPER(?)" to params
        }
        return cond to params
    }

    private fun getRowIdRange(tableName: String): Pair<Long, Long>? {
        return try {
            database?.rawQuery("SELECT MIN(rowid), MAX(rowid) FROM $tableName", null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                        cursor.getLong(0) to cursor.getLong(1)
                    } else null
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read rowid range for $tableName", e)
            null
        }
    }

    private fun runChunkedPaginatedSearch(
        tableName: String,
        conditions: List<String>,
        args: MutableList<String>,
        offset: Int,
        limit: Int,
        searchCols: String,
        outerCols: String
    ): List<Map<String, Any?>> {
        val totalNeeded = offset + limit
        val whereClause = conditions.joinToString(" OR ")

        val rowIdRange = getRowIdRange(tableName) ?: run {
            val fallbackQuery =
                "SELECT $outerCols, g.latitude, g.longitude FROM (SELECT $searchCols FROM $tableName n WHERE ($whereClause) ORDER BY n.rowid LIMIT $limit OFFSET $offset) inner_query LEFT JOIN geo g ON inner_query.BSSID = g.BSSID"
            return database?.rawQuery(fallbackQuery, args.toTypedArray())?.use { cursor ->
                cursor.toSearchResultsRaw()
            } ?: emptyList()
        }

        val results = mutableListOf<Map<String, Any?>>()
        var chunkStart = rowIdRange.first
        val searchStart = System.currentTimeMillis()

        while (chunkStart <= rowIdRange.second && results.size < totalNeeded) {
            if (searchCancelled) {
                Log.d(TAG, "Search cancelled, aborting chunked scan at rowid=$chunkStart")
                return emptyList()
            }

            val chunkEnd = chunkStart + SEARCH_CHUNK_ROWS
            val remaining = totalNeeded - results.size
            val chunkArgs = args.toMutableList().apply {
                add(chunkStart.toString())
                add(chunkEnd.toString())
                add(remaining.toString())
            }
            val chunkSql =
                "SELECT $outerCols, g.latitude, g.longitude FROM (SELECT $searchCols FROM $tableName n WHERE ($whereClause) AND n.rowid >= ? AND n.rowid < ? ORDER BY n.rowid LIMIT ?) inner_query LEFT JOIN geo g ON inner_query.BSSID = g.BSSID"

            val chunkResults =
                database?.rawQuery(chunkSql, chunkArgs.toTypedArray())?.use { cursor ->
                    cursor.toSearchResultsRaw()
                } ?: emptyList()

            results.addAll(chunkResults)
            chunkStart = chunkEnd
        }

        Log.d(
            TAG,
            "Chunked search collected ${results.size} results in ${System.currentTimeMillis() - searchStart}ms"
        )
        return results.drop(offset).take(limit)
    }

    private fun getValidSearchColumns(tableName: String): Pair<String, String> {
        validSearchColumnsCache[tableName]?.let { return it }

        val allColumns = DatabaseIndices.SEARCH_COLUMNS.split(", ").map { it.removePrefix("n.") }
        val existing = database?.rawQuery("PRAGMA table_info($tableName)", null)?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        } ?: allColumns.toSet()

        val valid = allColumns.filter { it in existing }
        val searchCols = valid.joinToString(", ") { "n.$it" }
        val outerCols = valid.joinToString(", ") { "inner_query.$it" }
        Log.d(
            TAG, "getValidSearchColumns: table=$tableName, valid=${valid.size}/${
                allColumns.size
            } cols, hasNoWiFiKey=${"NoWiFiKey" in valid}, hasNoWPS=${"NoWPS" in valid}"
        )
        val result = searchCols to outerCols
        validSearchColumnsCache[tableName] = result
        return result
    }

    private fun isValidBssidQuery(query: String): Boolean {
        return when {
            query.matches(MAC_FORMAT_REGEX) -> true
            query.matches(MAC_HEX_12_REGEX) -> true
            query.matches(DECIMAL_REGEX) && query.length >= 6 -> true
            query.matches(MAC_HEX_MIN_REGEX) && query.length >= 2 -> true
            query.matches(MAC_FULL_REGEX) && query.replace("[:-]", "").length >= 2 -> true
            else -> false
        }
    }

    private fun macToDecimalSafe(mac: String): Long {
        return try {
            when {
                mac.contains(":") || mac.contains("-") ->
                    mac.replace(":", "").replace("-", "").toLong(16)

                mac.matches(DECIMAL_REGEX) -> mac.toLong()
                mac.matches(MAC_HEX_12_REGEX) -> mac.toLong(16)
                else -> -1L
            }
        } catch (e: NumberFormatException) {
            Log.d(TAG, "Could not convert MAC to decimal: $mac")
            -1L
        }
    }

    fun searchNetworksByBSSIDAndFieldsRaw(
        query: String,
        filters: Set<String>,
        searchMode: SearchMode
    ): List<Map<String, Any?>> {
        val tableName = DatabaseTypeUtils.getMainTableName(database!!)

        val allResults = mutableSetOf<Map<String, Any?>>()

        filters.forEach { field ->
            val fieldResults = when (field) {
                "BSSID" -> searchByBssidRaw(query, tableName, searchMode)
                "ESSID" -> searchByEssidRaw(query, tableName, searchMode)
                "WiFiKey" -> searchByWifiKeyRaw(query, tableName, searchMode)
                "WPSPIN" -> searchByWpsPinRaw(query, tableName)
                else -> emptyList()
            }
            allResults.addAll(fieldResults)
        }

        return allResults.distinctBy { "${it["BSSID"]}-${it["ESSID"]}" }
    }

    private fun searchByBssidRaw(
        query: String,
        tableName: String,
        searchMode: SearchMode
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()

        val possibleFormats = generateMacFormats(query)

        val (searchCols, _) = getValidSearchColumns(tableName)
        possibleFormats.forEach { format ->
            val decimalValue = macToDecimalSafe(format)
            if (decimalValue != -1L) {
                database?.rawQuery(
                    "SELECT $searchCols, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.BSSID = ?",
                    arrayOf(decimalValue.toString())
                )?.use { cursor ->
                    results.addAll(cursor.toSearchResultsRaw())
                }
            }
        }

        if (results.isEmpty()) {
            database?.rawQuery(
                "SELECT $searchCols, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE CAST(n.BSSID AS TEXT) LIKE ?",
                arrayOf("%$query%")
            )?.use { cursor ->
                results.addAll(cursor.toSearchResultsRaw())
            }
        }

        return results
    }

    private fun searchByEssidRaw(
        query: String,
        tableName: String,
        searchMode: SearchMode
    ): List<Map<String, Any?>> {
        val (searchArgs, essidCondition) = when (searchMode) {
            SearchMode.EXACT -> arrayOf(
                query,
                query.lowercase(),
                query.uppercase()
            ) to "(n.ESSID = ? OR n.ESSID = ? OR n.ESSID = ?)"

            SearchMode.PREFIX -> arrayOf(
                query,
                "${query}\u0000",
                query.lowercase(),
                "${query.lowercase()}\u0000",
                query.uppercase(),
                "${query.uppercase()}\u0000"
            ) to "((n.ESSID >= ? AND n.ESSID < ?) OR (n.ESSID >= ? AND n.ESSID < ?) OR (n.ESSID >= ? AND n.ESSID < ?))"

            SearchMode.SUBSTRING -> arrayOf("%${query}%") to "n.ESSID LIKE ? ESCAPE '\\'"
        }

        val (searchCols, outerCols) = getValidSearchColumns(tableName)
        val sql =
            "SELECT $outerCols, g.latitude, g.longitude FROM (SELECT $searchCols FROM $tableName n WHERE $essidCondition) inner_query LEFT JOIN geo g ON inner_query.BSSID = g.BSSID"

        return database?.rawQuery(sql, searchArgs)?.use { cursor ->
            cursor.toSearchResultsRaw()
        } ?: emptyList()
    }

    private fun searchByWifiKeyRaw(
        query: String,
        tableName: String,
        searchMode: SearchMode
    ): List<Map<String, Any?>> {
        val (searchArgs, wifiKeyCondition) = when (searchMode) {
            SearchMode.EXACT -> arrayOf(
                query,
                query.lowercase(),
                query.uppercase()
            ) to "(n.WiFiKey = ? OR n.WiFiKey = ? OR n.WiFiKey = ?)"

            SearchMode.PREFIX -> arrayOf(
                query,
                "${query}\u0000",
                query.lowercase(),
                "${query.lowercase()}\u0000",
                query.uppercase(),
                "${query.uppercase()}\u0000"
            ) to "((n.WiFiKey >= ? AND n.WiFiKey < ?) OR (n.WiFiKey >= ? AND n.WiFiKey < ?) OR (n.WiFiKey >= ? AND n.WiFiKey < ?))"

            SearchMode.SUBSTRING -> arrayOf("%${query}%") to "n.WiFiKey LIKE ? ESCAPE '\\'"
        }

        val (searchCols, outerCols) = getValidSearchColumns(tableName)
        val sql =
            "SELECT $outerCols, g.latitude, g.longitude FROM (SELECT $searchCols FROM $tableName n WHERE $wifiKeyCondition) inner_query LEFT JOIN geo g ON inner_query.BSSID = g.BSSID"

        return database?.rawQuery(sql, searchArgs)?.use { cursor ->
            cursor.toSearchResultsRaw()
        } ?: emptyList()
    }

    private fun searchByWpsPinRaw(query: String, tableName: String): List<Map<String, Any?>> {
        val (searchCols, _) = getValidSearchColumns(tableName)
        val sql =
            "SELECT $searchCols, g.latitude, g.longitude FROM $tableName n LEFT JOIN geo g ON n.BSSID = g.BSSID WHERE n.WPSPIN = ?"

        return database?.rawQuery(sql, arrayOf(query))?.use { cursor ->
            cursor.toSearchResultsRaw()
        } ?: emptyList()
    }

    private fun Cursor.toSearchResultsRaw(): List<Map<String, Any?>> = buildList {
        while (moveToNext()) {
            val result = buildMap {
                for (i in 0 until columnCount) {
                    val columnName = getColumnName(i)
                    val value = when (getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> getFloat(i)
                        else -> getString(i)
                    }
                    put(columnName, value)
                }
            }.toMutableMap()

            add(result)
        }
    }

    fun searchNetworksByBSSIDAndFields(
        query: String,
        filters: Set<String>,
        searchMode: SearchMode
    ): List<Map<String, Any?>> {
        val tableName = DatabaseTypeUtils.getMainTableName(database!!)

        val allResults = mutableSetOf<Map<String, Any?>>()

        filters.forEach { field ->
            val fieldResults = when (field) {
                "BSSID" -> searchByBssidRaw(query, tableName, searchMode)
                "ESSID" -> searchByEssidRaw(query, tableName, searchMode)
                "WiFiKey" -> searchByWifiKeyRaw(query, tableName, searchMode)
                "WPSPIN" -> searchByWpsPinRaw(query, tableName)
                else -> emptyList()
            }
            allResults.addAll(fieldResults)
        }

        return allResults.distinctBy { "${it["BSSID"]}-${it["ESSID"]}" }
    }

    private fun generateMacFormats(input: String): List<String> {
        val cleanInput = input.replace(HEX_CLEAN_REGEX, "").uppercase()
        val formats = mutableListOf<String>()

        formats.add(input.trim())

        when {
            input.matches(DECIMAL_REGEX) -> {
                formats.add(input)
                try {
                    val decimal = input.toLong()
                    val hex = String.format("%012X", decimal)
                    if (hex.length <= 12) {
                        formats.add(hex)
                        formats.add(hex.lowercase())
                        formats.add(hex.replace(HEX_PAIR_REGEX, "$1:").dropLast(1))
                        formats.add(hex.replace(HEX_PAIR_REGEX, "$1-").dropLast(1))
                    }
                } catch (e: NumberFormatException) {
                    Log.d(TAG, "Could not convert decimal $input to hex")
                }
                if (input.length == 12) {
                    try {
                        val hexDecimal = input.toLong(16)
                        formats.add(hexDecimal.toString())
                    } catch (e: NumberFormatException) {
                        Log.d(TAG, "Could not convert hex $input to decimal")
                    }
                }
            }

            input.matches(MAC_FORMAT_REGEX) -> {
                formats.add(input)
                formats.add(input.replace(":", "").replace("-", ""))
                try {
                    val decimal = input.replace(":", "").replace("-", "").toLong(16)
                    formats.add(decimal.toString())
                } catch (e: NumberFormatException) {
                    Log.d(TAG, "Could not convert MAC $input to decimal")
                }
            }

            input.matches(MAC_HEX_12_REGEX) -> {
                formats.add(input)
                formats.add(input.lowercase())
                formats.add(input.replace(HEX_PAIR_REGEX, "$1:").dropLast(1))
                formats.add(input.replace(HEX_PAIR_REGEX, "$1-").dropLast(1))
                try {
                    val decimal = input.toLong(16)
                    formats.add(decimal.toString())
                } catch (e: NumberFormatException) {
                    Log.d(TAG, "Could not convert hex $input to decimal")
                }
            }

            cleanInput.length == 12 -> {
                formats.add(cleanInput)
                formats.add(cleanInput.lowercase())
                formats.add(cleanInput.replace(HEX_PAIR_REGEX, "$1:").dropLast(1))
                formats.add(cleanInput.replace(HEX_PAIR_REGEX, "$1-").dropLast(1))
                try {
                    val decimal = cleanInput.toLong(16)
                    formats.add(decimal.toString())
                } catch (e: NumberFormatException) {
                    Log.d(TAG, "Could not convert hex $cleanInput to decimal")
                }
            }

            else -> {
                if (cleanInput.isNotEmpty() && cleanInput.length >= 2) {
                    formats.add(cleanInput)
                }
            }
        }

        return formats.filter { it.isNotEmpty() && it.length >= 2 }.distinct()
    }

    private fun macToDecimal(mac: String): Long {
        return try {
            val result = when {
                mac.contains(":") || mac.contains("-") ->
                    mac.replace(":", "").replace("-", "").toLong(16)

                mac.matches(DECIMAL_REGEX) -> mac.toLong()
                mac.matches(MAC_HEX_12_REGEX) -> mac.toLong(16)
                else -> throw NumberFormatException("Invalid MAC format: $mac")
            }
            result
        } catch (e: NumberFormatException) {
            -1
        }
    }

    fun decimalToMac(decimal: Long): String {
        return String.format("%012X", decimal)
            .replace(HEX_PAIR_REGEX, "$1:").dropLast(1)
    }

    override fun close() {
        clearCache()
        database?.close()
        database = null
        cachedIpRangeManager?.close()
        cachedIpRangeManager = null
        super.close()
    }

    companion object {
        private const val TAG = "SQLite3WiFiHelper"
        private const val SEARCH_CHUNK_ROWS = 1_000_000L
        private const val MAX_PREFIX_VARIANTS = 256
        private val DECIMAL_REGEX = Regex("[0-9]+")
        private val HEX_CLEAN_REGEX = Regex("[^a-fA-F0-9]")
        private val MAC_FORMAT_REGEX = Regex("([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})")
        private val MAC_HEX_12_REGEX = Regex("[0-9A-Fa-f]{12}")
        private val MAC_HEX_MIN_REGEX = Regex("[0-9A-Fa-f]+")
        private val MAC_FULL_REGEX = Regex("[0-9A-Fa-f:.-]+")
        private val HEX_PAIR_REGEX = Regex("(.{2})")

        fun deleteCachedDatabase(context: Context, dbUri: Uri) {
            val cacheDir = File(context.cacheDir, "CacheDB")
            val fileName = getFileNameFromUri(dbUri)
            val cachedFile = File(cacheDir, fileName)
            if (cachedFile.exists()) {
                cachedFile.delete()
                Log.d(TAG, "Deleted cached database file: ${cachedFile.path}")
            }

            val metadataFile = File(cacheDir, "${fileName}.metadata")
            if (metadataFile.exists()) {
                metadataFile.delete()
                Log.d(TAG, "Deleted metadata file: ${metadataFile.path}")
            }
        }

        private fun getFileNameFromUri(uri: Uri): String {
            val name = uri.lastPathSegment?.split("/")?.last() ?: "database"
            return if (name.endsWith(".sqlite", ignoreCase = true)) name else "$name.sqlite"
        }
    }

    suspend fun getIpRanges(
        latitude: Double,
        longitude: Double,
        radius: Double,
        useRir: Boolean = false,
        countPoints: Boolean = false
    ): List<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Getting IP ranges for lat=$latitude, lon=$longitude, radius=$radius")
            try {
                databaseLock.withLock {
                    val tableName = DatabaseTypeUtils.getMainTableName(database!!)
                    if (tableName == "unknown") {
                        Log.e(TAG, "Unknown database type for IP ranges")
                        return@withLock emptyList()
                    }

                    val latMin = latitude - (radius / 111.0)
                    val latMax = latitude + (radius / 111.0)
                    val lonMin = longitude - (radius / (111.0 * Math.cos(Math.toRadians(latitude))))
                    val lonMax = longitude + (radius / (111.0 * Math.cos(Math.toRadians(latitude))))

                    Log.d(TAG, "Search bounds: lat($latMin to $latMax), lon($lonMin to $lonMax)")

                    val ips = collectIpsInBounds(
                        tableName = tableName,
                        latMin = latMin,
                        latMax = latMax,
                        lonMin = lonMin,
                        lonMax = lonMax,
                        centerLat = latitude,
                        centerLon = longitude,
                        radiusKm = radius
                    )

                    val ranges = mutableListOf<Map<String, Any?>>()
                    val processedRanges = mutableSetOf<String>()
                    val ipRangeManager = IpRangeManager(context)
                    ipRangeManager.skipRirLookup = !useRir

                    var lastUpper = 0L
                    val sortedIps = ips.sorted()

                    for (ip in sortedIps) {
                        if (ip <= lastUpper) continue

                        val ipRange = ipRangeManager.getIpRangeInfo(ip)
                        if (ipRange != null) {
                            lastUpper = ipRange.endIP
                            val rangeString =
                                ipRangeManager.prettyRange(ipRange.startIP, ipRange.endIP)

                            if (!processedRanges.contains(rangeString)) {
                                processedRanges.add(rangeString)
                                val pointCount =
                                    if (countPoints) {
                                        countIpsInRange(sortedIps, ipRange.startIP, ipRange.endIP)
                                    } else {
                                        0
                                    }
                                ranges.add(
                                    mapOf(
                                        "range" to rangeString,
                                        "netname" to ipRange.netname,
                                        "descr" to ipRange.description,
                                        "country" to ipRange.country,
                                        "count" to pointCount
                                    )
                                )
                            }
                        }
                    }

                    ipRangeManager.close()
                    ranges.sortedBy { it["descr"] as String }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting IP ranges", e)
                emptyList()
            }
        }

    private fun countIpsInRange(sortedIps: List<Long>, startIP: Long, endIP: Long): Int {
        var count = 0
        for (ip in sortedIps) {
            if (ip > endIP) break
            if (ip >= startIP) count++
        }
        return count
    }

    private suspend fun collectIpsInBounds(
        tableName: String,
        latMin: Double,
        latMax: Double,
        lonMin: Double,
        lonMax: Double,
        centerLat: Double? = null,
        centerLon: Double? = null,
        radiusKm: Double? = null
    ): Set<Long> {
        val ips = mutableSetOf<Long>()
        val db = database ?: return ips

        if (hasQuadkey) {
            val zoomLevel = 23
            val qkMinStr = latLonToQuadkeyString(latMax, lonMin, zoomLevel)
            val qkMaxStr = latLonToQuadkeyString(latMin, lonMax, zoomLevel)
            val commonPrefix = findCommonPrefix(qkMinStr, qkMaxStr)

            val geoQuery: String
            val geoArgs: Array<String>
            if (commonPrefix.isNotEmpty() && commonPrefix.length >= 2) {
                geoQuery = DatabaseIndices.getGeoQueryWithPrefix(commonPrefix, zoomLevel)
                geoArgs = emptyArray()
            } else {
                var qkMin = qkMinStr
                var qkMax = qkMaxStr
                if (qkMin.length < zoomLevel) qkMin = qkMin + "0".repeat(zoomLevel - qkMin.length)
                if (qkMax.length < zoomLevel) qkMax = qkMax + "3".repeat(zoomLevel - qkMax.length)
                val decimalMin = java.lang.Long.parseLong(qkMin, 4)
                val decimalMax = java.lang.Long.parseLong(qkMax, 4)
                geoQuery = DatabaseIndices.getOptimalGeoQuery(true)
                geoArgs = arrayOf(
                    minOf(decimalMin, decimalMax).toString(),
                    maxOf(decimalMin, decimalMax).toString()
                )
            }

            val bssidSet = mutableSetOf<Long>()
            db.rawQuery(geoQuery, geoArgs)?.use { cursor ->
                val bssidIdx = cursor.getColumnIndex("BSSID")
                val latIdx = cursor.getColumnIndex("latitude")
                val lonIdx = cursor.getColumnIndex("longitude")
                if (bssidIdx < 0) return@use
                while (cursor.moveToNext()) {
                    val bssid = cursor.getLong(bssidIdx)
                    if (centerLat != null && centerLon != null && radiusKm != null) {
                        val lat = cursor.getDouble(latIdx)
                        val lon = cursor.getDouble(lonIdx)
                        if (calculateDistance(centerLat, centerLon, lat, lon) > radiusKm) continue
                    }
                    bssidSet.add(bssid)
                    if (bssidSet.size >= 1000) break
                }
            }

            if (bssidSet.isEmpty()) return ips

            for (chunk in bssidSet.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val ipQuery =
                    "SELECT DISTINCT n.IP, n.WANIP FROM $tableName n WHERE n.BSSID IN ($placeholders) AND (n.IP != 0 OR n.WANIP != 0) LIMIT 1000"
                db.rawQuery(ipQuery, chunk.map { it.toString() }.toTypedArray())?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val ip = cursor.getLong(0)
                        val wanip = cursor.getLong(1)
                        if (ip != 0L) ips.add(ip)
                        if (wanip != 0L) ips.add(wanip)
                    }
                }
                if (ips.size >= 1000) break
            }
        } else {
            val geoQuery = """
                SELECT DISTINCT n.IP, n.WANIP, g.latitude, g.longitude
                FROM $tableName n
                JOIN geo g ON n.BSSID = g.BSSID
                WHERE g.latitude IS NOT NULL
                AND g.longitude IS NOT NULL
                AND g.latitude != 0
                AND g.longitude != 0
                AND g.latitude BETWEEN ? AND ?
                AND g.longitude BETWEEN ? AND ?
                AND (n.IP != 0 OR n.WANIP != 0)
                LIMIT 1000
            """
            db.rawQuery(
                geoQuery,
                arrayOf(
                    latMin.toString(),
                    latMax.toString(),
                    lonMin.toString(),
                    lonMax.toString()
                )
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val ip = cursor.getLong(0)
                    val wanip = cursor.getLong(1)
                    if (centerLat != null && centerLon != null && radiusKm != null) {
                        val pointLat = cursor.getDouble(2)
                        val pointLon = cursor.getDouble(3)
                        if (calculateDistance(
                                centerLat,
                                centerLon,
                                pointLat,
                                pointLon
                            ) > radiusKm
                        ) continue
                    }
                    if (ip != 0L) ips.add(ip)
                    if (wanip != 0L) ips.add(wanip)
                }
            }
        }

        return ips
    }

    suspend fun getIpRangesForCurrentView(
        latMin: Double, latMax: Double, lonMin: Double, lonMax: Double
    ): List<com.lsd.wififrankenstein.ui.ipranges.IpRangeResult> =
        withContext(Dispatchers.IO) {
            Log.d(
                TAG,
                "Getting IP ranges for current view: latMin=$latMin, latMax=$latMax, lonMin=$lonMin, lonMax=$lonMax"
            )
            try {
                databaseLock.withLock {
                    val tableName = DatabaseTypeUtils.getMainTableName(database!!)
                    if (tableName == "unknown") {
                        Log.e(TAG, "Unknown database type for IP ranges")
                        return@withLock emptyList()
                    }

                    val ips = collectIpsInBounds(
                        tableName = tableName,
                        latMin = latMin,
                        latMax = latMax,
                        lonMin = lonMin,
                        lonMax = lonMax
                    )

                    val ranges = mutableListOf<com.lsd.wififrankenstein.ui.ipranges.IpRangeResult>()
                    val processedRanges = mutableSetOf<String>()

                    var lastUpper = 0L
                    val sortedIps = ips.sorted()

                    for (ip in sortedIps) {
                        if (ip <= lastUpper) continue

                        val ipRange = ipRangeManager.getIpRangeInfo(ip)
                        if (ipRange != null) {
                            lastUpper = ipRange.endIP
                            val rangeString =
                                ipRangeManager.prettyRange(ipRange.startIP, ipRange.endIP)

                            if (!processedRanges.contains(rangeString)) {
                                processedRanges.add(rangeString)
                                ranges.add(
                                    com.lsd.wififrankenstein.ui.ipranges.IpRangeResult(
                                        range = rangeString,
                                        netname = ipRange.netname,
                                        description = ipRange.description,
                                        country = ipRange.country,
                                        sourceName = "local"
                                    )
                                )
                            }
                        }
                    }

                    ipRangeManager.close()
                    ranges.sortedBy { it.range }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting IP ranges for current view", e)
                emptyList()
            }
        }

    suspend fun getIpRangesByTileRange(
        tileX1: Int, tileY1: Int,
        tileX2: Int, tileY2: Int,
        zoom: Int
    ): List<com.lsd.wififrankenstein.ui.ipranges.IpRangeResult> = withContext(Dispatchers.IO) {
        Log.d(
            TAG,
            "Getting IP ranges by tile range: ($tileX1,$tileY1)-($tileX2,$tileY2), zoom=$zoom"
        )
        try {
            databaseLock.withLock {
                val tableName = DatabaseTypeUtils.getMainTableName(database!!)
                if (tableName == "unknown") {
                    Log.e(TAG, "Unknown database type for tile-based IP ranges")
                    return@withLock emptyList()
                }

                val maxZoom = 23
                val qkMin = QuadkeyUtils.latLonToQuadkey(
                    QuadkeyUtils.tileXYToLat(tileY1, zoom),
                    QuadkeyUtils.tileXYToLon(tileX1, zoom),
                    maxZoom
                )
                val qkMax = QuadkeyUtils.latLonToQuadkey(
                    QuadkeyUtils.tileXYToLat(tileY2 + 1, zoom),
                    QuadkeyUtils.tileXYToLon(tileX2 + 1, zoom),
                    maxZoom
                )
                val qkLower = minOf(qkMin, qkMax)
                val qkUpper = maxOf(qkMin, qkMax)

                Log.d(TAG, "Tile IP ranges quadkey range: $qkLower .. $qkUpper")

                val bssidSet = mutableSetOf<Long>()
                val bssidQuery =
                    "SELECT DISTINCT BSSID FROM geo WHERE quadkey >= ? AND quadkey <= ?"
                database?.rawQuery(bssidQuery, arrayOf(qkLower.toString(), qkUpper.toString()))
                    ?.use { cursor ->
                        val bssidIdx = cursor.getColumnIndex("BSSID")
                        if (bssidIdx >= 0) {
                            while (cursor.moveToNext()) {
                                bssidSet.add(cursor.getLong(bssidIdx))
                            }
                        }
                    }

                Log.d(TAG, "Tile IP ranges found ${bssidSet.size} unique BSSIDs")

                if (bssidSet.isEmpty()) {
                    return@withLock emptyList()
                }

                val ips = mutableSetOf<Long>()
                for (chunk in bssidSet.chunked(500)) {
                    val bssidPlaceholders =
                        chunk.mapIndexed { index, _ -> "?$index" }.joinToString(",")
                    val ipQuery = """
                        SELECT DISTINCT n.IP, n.WANIP FROM $tableName n
                        WHERE n.BSSID IN ($bssidPlaceholders)
                        AND (n.IP != 0 OR n.WANIP != 0)
                        LIMIT 1000
                    """

                    val ipArgs = chunk.map { it.toString() }.toTypedArray()
                    database?.rawQuery(ipQuery, ipArgs)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val ip = cursor.getLong(0)
                            val wanip = cursor.getLong(1)
                            if (ip != 0L) ips.add(ip)
                            if (wanip != 0L) ips.add(wanip)
                        }
                    }
                    if (ips.size >= 1000) break
                }

                val ranges = mutableListOf<com.lsd.wififrankenstein.ui.ipranges.IpRangeResult>()
                val processedRanges = mutableSetOf<String>()
                var lastUpper = 0L
                val sortedIps = ips.sorted()

                for (ip in sortedIps) {
                    if (ip <= lastUpper) continue
                    val ipRange = ipRangeManager.getIpRangeInfo(ip)
                    if (ipRange != null) {
                        lastUpper = ipRange.endIP
                        val rangeString = ipRangeManager.prettyRange(ipRange.startIP, ipRange.endIP)
                        if (!processedRanges.contains(rangeString)) {
                            processedRanges.add(rangeString)
                            ranges.add(
                                com.lsd.wififrankenstein.ui.ipranges.IpRangeResult(
                                    range = rangeString,
                                    netname = ipRange.netname,
                                    description = ipRange.description,
                                    country = ipRange.country,
                                    sourceName = "local"
                                )
                            )
                        }
                    }
                }

                ipRangeManager.close()
                ranges.sortedBy { it.range }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP ranges by tile range", e)
            emptyList()
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun searchNetworksByAdvancedQuery(
        advancedQuery: AdvancedSearchQuery,
        offset: Int,
        limit: Int
    ): List<Map<String, Any?>> {
        val tableName = DatabaseTypeUtils.getMainTableName(database!!)

        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (advancedQuery.bssid.isNotBlank()) {
            if (advancedQuery.containsWildcards(advancedQuery.bssid)) {
                val processedBssid = advancedQuery.convertWildcards(advancedQuery.bssid)
                conditions.add("(CAST(n.BSSID AS TEXT) LIKE ? OR printf('%012X', n.BSSID) LIKE ?)")
                args.add(processedBssid)
                args.add(processedBssid)
            } else {
                val possibleFormats = generateMacFormats(advancedQuery.bssid)
                var bssidConditionAdded = false

                possibleFormats.forEach { format ->
                    val decimalValue = macToDecimalSafe(format)
                    if (decimalValue != -1L) {
                        conditions.add("n.BSSID = ?")
                        args.add(decimalValue.toString())
                        bssidConditionAdded = true
                    }
                }

                if (!bssidConditionAdded) {
                    conditions.add("CAST(n.BSSID AS TEXT) LIKE ?")
                    args.add("%${advancedQuery.bssid}%")
                }
            }
        }

        if (advancedQuery.essid.isNotBlank()) {
            val processedEssid = if (advancedQuery.containsWildcards(advancedQuery.essid)) {
                advancedQuery.convertWildcards(advancedQuery.essid)
            } else {
                "%${advancedQuery.essid}%"
            }

            if (advancedQuery.caseSensitive) {
                conditions.add("n.ESSID LIKE ? COLLATE BINARY")
                args.add(processedEssid)
            } else {
                conditions.add("n.ESSID LIKE ?")
                args.add(processedEssid)
            }
        }

        if (advancedQuery.password.isNotBlank()) {
            val processedPassword = if (advancedQuery.containsWildcards(advancedQuery.password)) {
                advancedQuery.convertWildcards(advancedQuery.password)
            } else {
                "%${advancedQuery.password}%"
            }

            if (advancedQuery.caseSensitive) {
                conditions.add("(n.WiFiKey LIKE ? COLLATE BINARY OR n.Authorization LIKE ? COLLATE BINARY)")
                args.add(processedPassword)
                args.add(processedPassword)
            } else {
                conditions.add("(n.WiFiKey LIKE ? OR n.Authorization LIKE ?)")
                args.add(processedPassword)
                args.add(processedPassword)
            }
        }

        if (advancedQuery.wpsPin.isNotBlank()) {
            if (advancedQuery.containsWildcards(advancedQuery.wpsPin)) {
                val processedWpsPin = advancedQuery.convertWildcards(advancedQuery.wpsPin)
                conditions.add("(n.WPSPIN IS NOT NULL AND printf('%08d', CAST(n.WPSPIN AS INTEGER)) LIKE ?)")
                args.add(processedWpsPin)
            } else {
                conditions.add("n.WPSPIN = ?")
                args.add(advancedQuery.wpsPin)
            }
        }

        if (conditions.isEmpty()) return emptyList()

        val (searchCols, outerCols) = getValidSearchColumns(tableName)
        val baseQuery =
            "SELECT $outerCols, g.latitude, g.longitude FROM (SELECT $searchCols FROM $tableName n WHERE (${
                conditions.joinToString(" AND ")
            }) ORDER BY n.rowid LIMIT $limit OFFSET $offset) inner_query LEFT JOIN geo g ON inner_query.BSSID = g.BSSID"

        return database?.rawQuery(baseQuery, args.toTypedArray())?.use { cursor ->
            cursor.toSearchResultsRaw()
        } ?: emptyList()
    }

    fun clearCache() {
        resultsCache.clear()
        validSearchColumnsCache.clear()
    }
}
