package com.lsd.wififrankenstein.ui.wifimap

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentWifiMapBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

class WiFiMapFragment : Fragment() {
    private val TAG = "WiFiMapFragment"

    private var _binding: FragmentWifiMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WiFiMapViewModel by viewModels()
    private lateinit var databaseAdapter: MapDatabaseAdapter
    private val selectedDatabases = mutableSetOf<DbItem>()
    private var selectionRestored = false
    private var restoreJob: Job? = null
    private var isDatabasesExpanded = true
    private var isRadiusVisible = false
    private val selectedIpRanges = mutableSetOf<Int>()
    private var updateJob: Job? = null
    private var isClustersPreventMerged = false
    private lateinit var canvasOverlay: EfficientCanvasOverlay

    private lateinit var userLocationManager: UserLocationManager
    private var userLocationMarker: Marker? = null

    private var currentIndexingDb: DbItem? = null

    private var lastMapUpdateTime = 0L
    private val MAP_UPDATE_DEBOUNCE_MS = 100L
    private var lastUpdateZoom = -1.0
    private var lastUpdateCenter: GeoPoint? = null
    private var lastClusterUpdateZoom = -1.0
    private var lastClusterUpdateCenter: GeoPoint? = null
    private var lastZoomLevel: Double = -1.0

    private var isUserInteracting = false
    private var interactionTimer: Job? = null
    private var bounceJob: Job? = null

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null
    private lateinit var offlineMapManager: OfflineMapManager
    private var radiusCircleOverlay: Overlay? = null
    private var ipRangesAdapter: SimpleIpRangesAdapter? = null
    private var ipRangesLayoutManager: LinearLayoutManager? = null


    private val MIN_UPDATE_DELAY = 250L

    private var lastInteractionTime = 0L

    companion object {
        private const val DEFAULT_ZOOM = 5.0
        private const val DEFAULT_LAT = 55.7558
        private const val DEFAULT_LON = 37.6173
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWifiMapBinding.inflate(inflater, container, false)

        offlineMapManager = OfflineMapManager(requireContext())
        setupRecyclerView()
        setupCollapsibleCards()
        setupBottomSheet()
        startBounceAnimation()
        setupOfflineMaps()
        observeViewModel()
        setupToggleClusterButton()
        setupLocationButton()
        setupUserLocation()
        setupIpRangesPanel()

        viewLifecycleOwner.lifecycleScope.launch {
            if (_binding == null) return@launch
            Configuration.getInstance().load(
                requireContext(),
                PreferenceManager.getDefaultSharedPreferences(requireContext())
            )
            setupMap()
            applyNavigationArgs()
        }

        return binding.root
    }

    private fun applyNavigationArgs() {
        val lat = arguments?.getFloat("latitude", 0.0f) ?: 0.0f
        val lon = arguments?.getFloat("longitude", 0.0f) ?: 0.0f
        if (lat != 0.0f || lon != 0.0f) {
            centerOn(lat.toDouble(), lon.toDouble(), 18.0)
        }
    }

    fun centerOn(latitude: Double, longitude: Double, zoom: Double = 15.0) {
        if (_binding == null) return
        binding.map.post {
            if (_binding == null) return@post
            val point = GeoPoint(latitude, longitude)
            binding.map.controller.animateTo(point, zoom, 400L)
            val marker = Marker(binding.map).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_default)
                title = getString(R.string.wm_marker_title, latitude, longitude)
            }
            binding.map.overlays.add(marker)
            binding.map.invalidate()
        }
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomPanel)
        bottomSheetBehavior?.apply {
            peekHeight = resources.getDimensionPixelSize(R.dimen.dp_65)
            isHideable = false
            state = BottomSheetBehavior.STATE_COLLAPSED

            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    when (newState) {
                        BottomSheetBehavior.STATE_EXPANDED -> {
                            binding.dragHandle.isClickable = true
                        }

                        BottomSheetBehavior.STATE_COLLAPSED -> {
                            binding.dragHandle.isClickable = true
                        }
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                }
            })
        }

        binding.dragHandle.setOnClickListener {
            bottomSheetBehavior?.let { behavior ->
                when (behavior.state) {
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    }

                    else -> {
                        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    }
                }
            }
        }
    }

    private fun startBounceAnimation() {
        binding.bottomPanel.setOnTouchListener { _, _ ->
            bounceJob?.cancel()
            binding.bottomPanel.setOnTouchListener(null)
            binding.bottomPanel.translationY = 0f
            false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(800)
            if (_binding == null) return@launch
            val sheet = binding.bottomPanel
            sheet.translationY = 0f
            val density = resources.displayMetrics.density

            bounceJob = viewLifecycleOwner.lifecycleScope.launch {
                val bounceHeights = intArrayOf(
                    (120 * density).toInt(),
                    (80 * density).toInt(),
                    (40 * density).toInt()
                )

                for (height in bounceHeights) {
                    val upAnimator = android.animation.ValueAnimator.ofFloat(0f, -height.toFloat())
                    upAnimator.duration = 180L
                    upAnimator.interpolator =
                        android.view.animation.AccelerateDecelerateInterpolator()
                    upAnimator.addUpdateListener { anim ->
                        sheet.translationY = anim.animatedValue as Float
                    }
                    upAnimator.start()
                    delay(220)

                    val downAnimator =
                        android.animation.ValueAnimator.ofFloat(-height.toFloat(), 0f)
                    downAnimator.duration = 120L
                    downAnimator.interpolator = android.view.animation.BounceInterpolator()
                    downAnimator.addUpdateListener { anim ->
                        sheet.translationY = anim.animatedValue as Float
                    }
                    downAnimator.start()
                    delay(300)
                }

                sheet.translationY = 0f
            }
        }
    }

    private fun setupToggleClusterButton() {
        isClustersPreventMerged = viewModel.getPreventClusterMerge()
        updateFabIcon()

        binding.fabToggleClusters.setOnClickListener {
            isClustersPreventMerged = !isClustersPreventMerged
            viewModel.setPreventClusterMerge(isClustersPreventMerged)
            updateFabIcon()

            val message = if (isClustersPreventMerged)
                getString(R.string.clusters_separated)
            else
                getString(R.string.clusters_merged)

            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

            viewModel.clearCache()
            clearMarkers()
            scheduleMapUpdate(true)
        }
    }

    private fun updateFabIcon() {
        val ctx = context ?: return
        when {
            isClustersPreventMerged -> {
                binding.fabToggleClusters.setImageResource(R.drawable.ic_layers)
                binding.fabToggleClusters.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.green_500))
            }

            else -> {
                binding.fabToggleClusters.setImageResource(R.drawable.ic_layers)
                binding.fabToggleClusters.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.blue_500))
            }
        }
    }

    private fun setupMap() {
        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setMultiTouchControls(true)


            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LON))

            addOnFirstLayoutListener { _, _, _, _, _ ->
                if (zoomLevelDouble < 3.0) {
                    controller.setZoom(DEFAULT_ZOOM)
                    controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LON))
                }
            }

            canvasOverlay = EfficientCanvasOverlay { point ->
                if (point.isCluster) {
                    binding.map.controller.animateTo(
                        GeoPoint(point.latitude, point.longitude),
                        zoomLevelDouble + 1.0,
                        400L
                    )
                } else {
                    lifecycleScope.launch {
                        viewModel.loadPointInfoByBssid(
                            point.bssidDecimal,
                            point.databaseId,
                            point.latitude,
                            point.longitude
                        )
                    }
                }
            }

            overlays.add(canvasOverlay)

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent): Boolean {
                    isUserInteracting = true
                    lastInteractionTime = System.currentTimeMillis()
                    interactionTimer?.cancel()

                    interactionTimer = viewLifecycleOwner.lifecycleScope.launch {
                        delay(MIN_UPDATE_DELAY)
                        isUserInteracting = false
                        scheduleMapUpdate()
                    }
                    return true
                }

                override fun onZoom(event: ZoomEvent): Boolean {
                    isUserInteracting = true
                    lastInteractionTime = System.currentTimeMillis()
                    lastZoomLevel = binding.map.zoomLevelDouble
                    interactionTimer?.cancel()

                    interactionTimer = viewLifecycleOwner.lifecycleScope.launch {
                        delay(MIN_UPDATE_DELAY)
                        isUserInteracting = false
                        scheduleMapUpdate()
                    }
                    return true
                }
            })
        }
    }

    private fun scheduleMapUpdate(forceUpdate: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val currentZoom = binding.map.zoomLevelDouble
        val currentCenter = binding.map.mapCenter as? GeoPoint

        if (!forceUpdate && isUserInteracting) {
            return
        }

        if (!forceUpdate && (currentTime - lastInteractionTime < MIN_UPDATE_DELAY)) {
            return
        }

        if (!forceUpdate && !shouldUpdateClusters(currentZoom, currentCenter)) {
            return
        }

        updateJob?.cancel()
        lastMapUpdateTime = currentTime

        updateJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!forceUpdate) {
                delay(50)
            }

            if (!isUserInteracting || forceUpdate) {
                updateVisiblePoints()
            }
        }
    }

    private fun shouldUpdateClusters(currentZoom: Double, currentCenter: GeoPoint?): Boolean {
        if (currentCenter == null) {
            return true
        }

        if (lastClusterUpdateZoom < 0) {
            lastClusterUpdateZoom = currentZoom
            lastClusterUpdateCenter = currentCenter
            return true
        }

        val zoomDiff = kotlin.math.abs(currentZoom - lastClusterUpdateZoom)

        if (zoomDiff >= 0.1) {
            lastClusterUpdateZoom = currentZoom
            lastClusterUpdateCenter = currentCenter
            return true
        }

        val centerDistance = lastClusterUpdateCenter?.let { lastCenter ->
            val geoPoint1 = GeoPoint(lastCenter.latitude, lastCenter.longitude)
            val geoPoint2 = GeoPoint(currentCenter.latitude, currentCenter.longitude)
            geoPoint1.distanceToAsDouble(geoPoint2)
        } ?: Double.MAX_VALUE

        val currentBounds = binding.map.boundingBox
        val viewportDiagonal = currentBounds?.let {
            val corner1 = GeoPoint(it.latNorth, it.lonWest)
            val corner2 = GeoPoint(it.latSouth, it.lonEast)
            corner1.distanceToAsDouble(corner2)
        } ?: 0.0

        val movementThreshold = when {
            currentZoom >= 16.0 -> viewportDiagonal * 0.15
            currentZoom >= 14.0 -> viewportDiagonal * 0.2
            currentZoom >= 12.0 -> viewportDiagonal * 0.25
            currentZoom >= 10.0 -> viewportDiagonal * 0.3
            else -> viewportDiagonal * 0.35
        }

        val shouldUpdate = centerDistance > movementThreshold

        if (shouldUpdate) {
            lastClusterUpdateZoom = currentZoom
            lastClusterUpdateCenter = currentCenter
        }

        return shouldUpdate
    }

    private fun setupRecyclerView() {
        binding.databasesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            databaseAdapter = MapDatabaseAdapter(
                emptyList(),
                selectedDatabases,
                {
                    syncSelectedDatabaseIds()
                    viewModel.forceRefresh()
                    clearMarkers()
                    resetMapState()

                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(100)
                        scheduleMapUpdate(true)
                        delay(500)
                        if (selectedDatabases.isNotEmpty()) {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.map_data_refreshed),
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                viewModel,
                context
            )
            adapter = databaseAdapter
        }

        viewModel.availableDatabases.observe(viewLifecycleOwner) { databases ->
            val filteredDatabases = databases
            databaseAdapter = MapDatabaseAdapter(
                filteredDatabases,
                selectedDatabases,
                {
                    syncSelectedDatabaseIds()
                    viewModel.forceRefresh()
                    clearMarkers()
                    resetMapState()

                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(100)
                        scheduleMapUpdate(true)
                        delay(500)
                        if (selectedDatabases.isNotEmpty()) {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.map_data_refreshed),
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                viewModel,
                context ?: return@observe
            )
            binding.databasesRecyclerView.adapter = databaseAdapter
            restoreSelectedDatabases(databases)
        }
    }

    private fun restoreSelectedDatabases(available: List<DbItem>) {
        restoreJob?.cancel()
        restoreJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            if (_binding == null || selectionRestored) return@launch
            selectionRestored = true

            val savedIds = viewModel.getSavedSelectedDatabaseIds()
            if (savedIds.isEmpty()) return@launch

            val availableIds = available.map { it.id }.toSet()
            savedIds.filter { it in availableIds }
                .forEach { id ->
                    val dbItem = available.find { it.id == id } ?: return@forEach
                    if (selectedDatabases.any { it.id == id }) return@forEach

                    when (dbItem.dbType) {
                        DbType.LOCAL_APP_DB, DbType.HANDSHAKE_STORAGE,
                        DbType.SQLITE_FILE_P3WIFI, DbType.SMARTLINK_SQLITE_FILE_P3WIFI,
                        DbType.WIFI_API -> {
                            selectedDatabases.add(dbItem)
                        }

                        else -> {
                            viewModel.handleCustomDbSelection(dbItem, true, selectedDatabases)
                            return@forEach
                        }
                    }
                }

            if (selectedDatabases.isNotEmpty()) {
                syncSelectedDatabaseIds()
                databaseAdapter.notifyDataSetChanged()
                clearMarkers()
                resetMapState()
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(100)
                    scheduleMapUpdate(true)
                }
            }
        }
    }

    private fun setupLocationButton() {
        binding.fabLocation.setOnClickListener {
            userLocationMarker?.let { marker ->
                binding.map.controller.animateTo(marker.position, 18.0, 400L)
            } ?: run {
                Snackbar.make(
                    binding.root,
                    getString(R.string.location_requested),
                    Snackbar.LENGTH_SHORT
                ).show()
                userLocationManager.requestSingleLocationUpdate()
            }
        }
    }

    private fun setupUserLocation() {
        userLocationManager = UserLocationManager(requireContext())

        userLocationManager.userLocation.observe(viewLifecycleOwner) { location ->
            location?.let {
                updateUserLocationMarker(it)

                if (userLocationMarker == null) {
                    binding.map.controller.animateTo(it, 18.0, 400L)
                }
            }
        }

        userLocationManager.locationError.observe(viewLifecycleOwner) { errorKey ->
            val errorMessage = when (errorKey) {
                "location_services_disabled" -> getString(R.string.location_services_disabled)
                "location_permission_denied" -> getString(R.string.location_permission_denied)
                "location_not_available" -> getString(R.string.location_not_available)
                "location_updates_failed" -> getString(R.string.location_updates_failed)
                "location_timeout" -> getString(R.string.location_timeout)
                else -> errorKey
            }
            Snackbar.make(binding.root, errorMessage, Snackbar.LENGTH_LONG).show()
        }

        userLocationManager.permissionRequired.observe(viewLifecycleOwner) { permissions ->
            requestLocationPermissions(permissions)
        }
    }

    private fun requestLocationPermissions(permissions: Array<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(permissions, LOCATION_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    userLocationManager.resetPermissionState()
                    userLocationManager.startLocationUpdates()
                } else {
                    userLocationManager.resetPermissionState()
                    Snackbar.make(binding.root, getString(R.string.location_permission_denied), Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateUserLocationMarker(location: GeoPoint) {
        if (_binding == null) return

        userLocationMarker?.let { marker ->
            binding.map.overlays.remove(marker)
        }

        userLocationMarker = Marker(binding.map).apply {
            position = location
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_location)?.apply {
                setTint(ContextCompat.getColor(requireContext(), R.color.blue_500))
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }

        binding.map.overlays.add(userLocationMarker)
        binding.map.invalidate()
    }

    private fun showCreateIndexesDialog(dbItem: DbItem) {
        val ctx = context ?: return
        currentIndexingDb = dbItem

        val indexLevels = arrayOf(
            getString(R.string.index_level_full_option),
            getString(R.string.index_level_basic_option),
            getString(R.string.index_level_none_option)
        )

        var selectedLevel = 1

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.select_index_level)
            .setMessage(R.string.create_indexes_message)
            .setSingleChoiceItems(indexLevels, selectedLevel) { _, which ->
                selectedLevel = which
            }
            .setPositiveButton(R.string.create_indexes) { _, _ ->
                val level = when (selectedLevel) {
                    0 -> "FULL"
                    1 -> "BASIC"
                    2 -> "NONE"
                    else -> "BASIC"
                }

                val prefKey = if (dbItem.dbType == DbType.LOCAL_APP_DB) {
                    "local_db_index_level"
                } else {
                    "custom_db_index_level"
                }

                requireContext().getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
                    .edit {
                        putString(prefKey, level)
                    }

                if (level == "NONE") {
                    selectedDatabases.remove(dbItem)
                    syncSelectedDatabaseIds()
                    databaseAdapter.notifyDataSetChanged()
                    currentIndexingDb = null
                    return@setPositiveButton
                }

                showIndexingProgress()
                lifecycleScope.launch {
                    if (dbItem.dbType == DbType.LOCAL_APP_DB) {
                        viewModel.createLocalDbIndexes()
                    } else {
                        viewModel.createCustomDbIndexes(dbItem)
                    }
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                Log.d(TAG, "User cancelled index creation for ${dbItem.id}")
                selectedDatabases.remove(dbItem)
                syncSelectedDatabaseIds()
                databaseAdapter.notifyDataSetChanged()
                currentIndexingDb = null
            }
            .setCancelable(false)
            .show()
    }

    private var indexingDialog: AlertDialog? = null

    private fun showIndexingProgress() {
        val ctx = context ?: return
        indexingDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.creating_indexes)
            .setView(R.layout.dialog_indexing_progress)
            .setCancelable(false)
            .show()
    }

    private fun updateIndexingProgress(progress: Int) {
        val progressBar = indexingDialog?.findViewById<ProgressBar>(R.id.progressBar)
        if (progressBar != null) {
            progressBar.progress = progress
        }

        if (progress >= 100) {
            indexingDialog?.dismiss()

            currentIndexingDb?.let { dbItem ->
                selectedDatabases.add(dbItem)
                syncSelectedDatabaseIds()
                databaseAdapter.notifyDataSetChanged()

                clearMarkers()
                scheduleMapUpdate(true)
            } ?: Log.e(TAG, "currentIndexingDb is null after indexing completion")

            currentIndexingDb = null
        }
    }


    private fun resetMapState() {
        isUserInteracting = false
        lastInteractionTime = 0L
        lastMapUpdateTime = 0L
        lastUpdateZoom = -1.0
        lastUpdateCenter = null
        lastClusterUpdateZoom = -1.0
        lastClusterUpdateCenter = null

        updateJob?.cancel()
        updateJob = null

        viewModel.clearCache()
        clearMarkers()
    }

    private fun showCorruptionDialog(event: WiFiMapViewModel.CorruptionEvent) {
        val ctx = context ?: return

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.db_corrupted_title)
            .setMessage(
                if (event.canRecover) {
                    getString(R.string.db_corrupted_message_recoverable, event.sourcePath)
                } else {
                    getString(R.string.db_corrupted_message_unrecoverable)
                }
            )
            .apply {
                if (event.canRecover) {
                    setPositiveButton(R.string.db_recovery) { _, _ ->
                        viewModel.attemptRecovery(event.dbItem)
                    }
                }
                setNegativeButton(R.string.db_delete_corrupted) { _, _ ->
                    viewModel.deleteCorruptedDatabase(event.dbItem)
                }
                setNeutralButton(android.R.string.cancel, null)
            }
            .setCancelable(false)
            .show()
    }

    private fun observeViewModel() {
        viewModel.loadingProgress.observe(viewLifecycleOwner) { progress ->
            if (progress > 0 && progress < 100) {
                binding.loadingIndicator.startAnimation()
            } else if (progress == 100) {
                binding.loadingIndicator.stopAnimation()
            }
        }

        viewModel.selectedPoint.observe(viewLifecycleOwner) { point ->
            if (point != null && point.isDataLoaded) {
                showNetworkInfo(point)
            }
        }

        viewModel.points.observe(viewLifecycleOwner) { points ->
            updateMarkers(points)
        }

        viewModel.ipRanges.observe(viewLifecycleOwner) { ranges ->
            updateIpRangesDisplay(ranges)
        }

        viewModel.ipRangesLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                binding.ipRangesProgress.visibility = View.VISIBLE
            } else {
                binding.ipRangesProgress.visibility = View.GONE
            }
        }

        viewModel.addReadOnlyDb.observe(viewLifecycleOwner) { dbItem ->
            if (!selectedDatabases.any { it.id == dbItem.id }) {
                selectedDatabases.add(dbItem)
                syncSelectedDatabaseIds()
                databaseAdapter.notifyDataSetChanged()
                clearMarkers()
                resetMapState()
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(100)
                    scheduleMapUpdate(true)
                }
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { errorMsg ->
            if (errorMsg.isNotEmpty()) {
                Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_LONG).show()
            }
        }

        viewModel.indexingProgress.observe(viewLifecycleOwner) { progress ->
            updateIndexingProgress(progress)
        }

        viewModel.showIndexingDialog.observe(viewLifecycleOwner) { dbItem ->
            dbItem?.let { showCreateIndexesDialog(it) }
        }

        viewModel.corruptionEvent.observe(viewLifecycleOwner) { event ->
            event?.let { showCorruptionDialog(it) }
        }
    }

    private fun updateVisiblePoints() {
        if (isUserInteracting) {
            return
        }

        val zoom = binding.map.zoomLevelDouble
        lastUpdateZoom = zoom
        lastUpdateCenter = binding.map.mapCenter as? GeoPoint
        val minZoom = viewModel.getMinZoomForMarkers()
        val boundingBox = binding.map.boundingBox

        if (selectedDatabases.isEmpty()) {
            clearMarkers()
            return
        }

        if (zoom < minZoom) {
            clearMarkers()
            return
        }

        if (boundingBox == null) {
            return
        }

        updateJob?.cancel()

        updateJob = lifecycleScope.launch {
            try {
                val scatterMode = isClustersPreventMerged || zoom >= 17.0
                viewModel.loadPointsInBoundingBox(
                    boundingBox,
                    zoom,
                    selectedDatabases,
                    scatterMode
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in updateVisiblePoints", e)
            }
        }
    }

    private fun updateMarkers(visiblePoints: List<MapPoint>) {
        viewModel.updatePointCounts(visiblePoints)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            canvasOverlay.updatePoints(visiblePoints)
            binding.map.postInvalidate()
        }
    }

    private fun setupCollapsibleCards() {
        binding.databasesCollapseButton.setOnClickListener {
            isDatabasesExpanded = !isDatabasesExpanded
            binding.databasesRecyclerView.visibility =
                if (isDatabasesExpanded) View.VISIBLE else View.GONE
            binding.databasesCollapseButton.setImageResource(
                if (isDatabasesExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
            binding.databasesCollapseButton.contentDescription = getString(
                if (isDatabasesExpanded) R.string.collapse else R.string.expand
            )
        }

        binding.searchButton.setOnClickListener {
            searchIpRanges()
        }
    }

    private fun setupIpRangesPanel() {
        binding.map.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateCenterText()
            if (isRadiusVisible) updateRadiusCircle()
        }


        isRadiusVisible = viewModel.showRadiusCircle
        binding.switchShowRadius.isChecked = viewModel.showRadiusCircle
        binding.radiusInput.setText(viewModel.searchRadius.toInt().toString())

        binding.radiusInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isRadiusVisible) updateRadiusCircle()
            }

            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.toDoubleOrNull()?.let { viewModel.searchRadius = it.toFloat() }
            }
        })

        binding.switchRdapEnrichment.isChecked = viewModel.enableRdapEnrichment
        binding.switchRdapEnrichment.setOnCheckedChangeListener { _, isChecked ->
            viewModel.enableRdapEnrichment = isChecked
        }

        binding.switchIpRangeCounts.isChecked = viewModel.enableIpRangeCounts
        binding.switchIpRangeCounts.setOnCheckedChangeListener { _, isChecked ->
            viewModel.enableIpRangeCounts = isChecked
        }

        binding.switchShowRadius.setOnCheckedChangeListener { _, isChecked ->
            isRadiusVisible = isChecked
            viewModel.showRadiusCircle = isChecked
            if (isChecked) {
                updateRadiusCircle()
            } else {
                radiusCircleOverlay?.let { overlay ->
                    binding.map.overlays.remove(overlay)
                    radiusCircleOverlay = null
                }
                binding.map.invalidate()
            }
        }

        binding.buttonCopyAll.setOnClickListener {
            copyAllIpRanges()
        }

        binding.buttonCopySelected.setOnClickListener {
            copySelectedIpRanges()
        }

        binding.buttonCopyAllTop.setOnClickListener {
            copyAllIpRanges()
        }

        binding.buttonCopySelectedTop.setOnClickListener {
            copySelectedIpRanges()
        }

        updateRadiusCircle()
    }

    private var offlineZonesList = mutableListOf<OfflineMapManager.OfflineZone>()
    private var offlineZonesAdapter: OfflineZoneAdapter? = null
    private var offlineEstimateJob: Job? = null

    private fun setupOfflineMaps() {
        offlineZonesAdapter = OfflineZoneAdapter(
            offlineZonesList,
            onDelete = { zone ->
                offlineMapManager.deleteZone(zone.id)
                updateOfflineZones()
            }
        )

        binding.offlineZonesRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.offlineZonesRecyclerView.adapter = offlineZonesAdapter

        updateOfflineZones()

        binding.buttonDownloadOffline.setOnClickListener {
            downloadOfflineZone()
        }

        binding.map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean {
                offlineEstimateJob?.cancel()
                offlineEstimateJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(500)
                    updateOfflineEstimate()
                }
                return false
            }

            override fun onZoom(event: ZoomEvent): Boolean {
                offlineEstimateJob?.cancel()
                offlineEstimateJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(500)
                    updateOfflineEstimate()
                }
                return false
            }
        })
    }

    private fun getOfflineZoomRange(currentZoom: Int): Pair<Int, Int> {
        val minZoom: Int
        val maxZoom: Int
        when {
            currentZoom >= 14 -> {
                minZoom = (currentZoom - 4).coerceAtLeast(OfflineMapManager.DEFAULT_MIN_ZOOM)
                maxZoom = (currentZoom + 1).coerceAtMost(OfflineMapManager.DEFAULT_MAX_ZOOM)
            }

            currentZoom >= 12 -> {
                minZoom = (currentZoom - 3).coerceAtLeast(OfflineMapManager.DEFAULT_MIN_ZOOM)
                maxZoom = (currentZoom + 1).coerceAtMost(OfflineMapManager.DEFAULT_MAX_ZOOM)
            }

            else -> {
                minZoom = (currentZoom - 1).coerceAtLeast(OfflineMapManager.DEFAULT_MIN_ZOOM)
                maxZoom = (currentZoom + 1).coerceAtMost(OfflineMapManager.DEFAULT_MAX_ZOOM)
            }
        }
        return Pair(minZoom, maxZoom)
    }

    private fun updateOfflineEstimate() {
        try {
            val bounds = binding.map.boundingBox ?: return
            val zoom = binding.map.zoomLevelDouble.toInt()
            val (minZoom, maxZoom) = getOfflineZoomRange(zoom)
            val count = offlineMapManager.estimateTileCount(bounds, minZoom, maxZoom)

            if (count > OfflineMapManager.MAX_TILES) {
                binding.offlineEstimateText.text =
                    getString(R.string.tile_count_too_many, count, OfflineMapManager.MAX_TILES)
                binding.offlineEstimateText.visibility = View.VISIBLE
                binding.buttonDownloadOffline.isEnabled = false
            } else {
                val estSize = estimateSize(count)
                binding.offlineEstimateText.text =
                    getString(R.string.tile_count_estimate, count, estSize)
                binding.offlineEstimateText.visibility = View.VISIBLE
                binding.buttonDownloadOffline.isEnabled = count > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateOfflineEstimate error", e)
            binding.offlineEstimateText.visibility = View.GONE
        }
    }

    private fun estimateSize(tileCount: Int): String {
        val avgTileSize = 150 * 1024L
        val totalBytes = tileCount * avgTileSize
        return when {
            totalBytes < 1024 * 1024 -> "${totalBytes / 1024} KB"
            else -> "%.0f MB".format(java.util.Locale.US, totalBytes / (1024.0 * 1024.0))
        }
    }

    private fun downloadOfflineZone() {
        val bounds = binding.map.boundingBox ?: run {
            Log.w(TAG, "downloadOfflineZone: map boundingBox is null")
            return
        }
        val zoom = binding.map.zoomLevelDouble.toInt()
        val (minZoom, maxZoom) = getOfflineZoomRange(zoom)

        val center = binding.map.mapCenter as? GeoPoint ?: run {
            Log.w(TAG, "downloadOfflineZone: mapCenter is null")
            return
        }
        val name =
            String.format(java.util.Locale.US, "%.2f, %.2f", center.latitude, center.longitude)

        Log.d(
            TAG,
            "downloadOfflineZone: bounds=[$bounds], zoom=$zoom, range=$minZoom-$maxZoom, center=$center"
        )

        binding.buttonDownloadOffline.isEnabled = false
        binding.offlineProgress.visibility = View.VISIBLE
        binding.offlineProgressText.visibility = View.VISIBLE
        binding.offlineProgressText.text = getString(R.string.offline_map_downloading, 0, 0)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = offlineMapManager.downloadZone(
                bounds = bounds,
                tileSourceName = "Mapnik",
                name = name,
                minZoom = minZoom,
                maxZoom = maxZoom,
                onProgress = { progress ->
                    val pct = (progress * 100).toInt()
                    binding.offlineProgress.post {
                        binding.offlineProgress.setProgress(pct, true)
                        binding.offlineProgressText.text =
                            getString(R.string.offline_map_downloading, pct, 100)
                    }
                }
            )

            binding.offlineProgress.visibility = View.GONE
            binding.offlineProgressText.visibility = View.GONE

            result.onSuccess { zone ->
                Snackbar.make(
                    binding.root,
                    getString(
                        R.string.offline_map_saved,
                        zone.tileCount,
                        offlineMapManager.getZoneSizeFormatted(zone)
                    ),
                    Snackbar.LENGTH_LONG
                ).show()
                updateOfflineZones()
            }.onFailure { error ->
                Snackbar.make(
                    binding.root,
                    error.message ?: getString(R.string.wm_error),
                    Snackbar.LENGTH_LONG
                ).show()
                binding.buttonDownloadOffline.isEnabled = true
            }

            binding.buttonDownloadOffline.isEnabled = true
        }
    }

    private fun updateOfflineZones() {
        offlineZonesList.clear()
        offlineZonesList.addAll(offlineMapManager.getZones())
        offlineZonesAdapter?.notifyDataSetChanged()

        if (offlineZonesList.isEmpty()) {
            binding.offlineZonesRecyclerView.visibility = View.GONE
        } else {
            binding.offlineZonesRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun updateCenterText() {
        val center = binding.map.mapCenter as? GeoPoint ?: return
        val lat = String.format(java.util.Locale.US, "%.4f", center.latitude)
        val lon = String.format(java.util.Locale.US, "%.4f", center.longitude)
        binding.centerText.text = getString(R.string.wm_center, lat, lon)
    }

    private fun updateRadiusCircle() {
        val center = binding.map.mapCenter as? GeoPoint ?: return
        val radiusKm = binding.radiusInput.text.toString().toDoubleOrNull() ?: 5.0
        val radiusMeters = radiusKm * 1000.0

        radiusCircleOverlay?.let { overlay ->
            binding.map.overlays.remove(overlay)
            radiusCircleOverlay = null
        }

        val paint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(60, 33, 150, 243)
            isAntiAlias = true
        }

        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.argb(200, 33, 150, 243)
            strokeWidth = 4f
            isAntiAlias = true
        }

        val overlay = object : Overlay() {
            override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
                if (shadow) return
                val projection = mapView.projection
                val screenPoint = projection.toPixels(center, null)
                val metersPerPixel =
                    156543.03 * Math.cos(center.latitude * Math.PI / 180.0) / Math.pow(
                        2.0,
                        mapView.zoomLevelDouble
                    )
                val radiusPx = (radiusMeters / metersPerPixel).toFloat()
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radiusPx, paint)
                canvas.drawCircle(
                    screenPoint.x.toFloat(),
                    screenPoint.y.toFloat(),
                    radiusPx,
                    strokePaint
                )
            }
        }

        radiusCircleOverlay = overlay
        binding.map.overlays.add(overlay)
        binding.map.invalidate()
    }

    private fun searchIpRanges() {
        val ctx = context ?: return
        val center = binding.map.mapCenter as? GeoPoint ?: run {
            Log.e(TAG, "searchIpRanges: mapCenter is null")
            return
        }
        val radius = binding.radiusInput.text.toString().toDoubleOrNull() ?: 5.0
        val hasApiSelected =
            viewModel.availableDatabases.value?.any { it.id in selectedDatabases.map { it.id } && it.dbType == DbType.WIFI_API } == true
        val maxRadius = if (hasApiSelected) 25.0 else 40.0
        val finalRadius = radius.coerceIn(0.1, maxRadius)

        if (radius > maxRadius) {
            val msg =
                if (hasApiSelected) getString(R.string.radius_limited_for_api) else getString(R.string.max_radius_40km)
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
        }

        try {
            viewModel.searchIpRangesByCenter(
                center.latitude,
                center.longitude,
                finalRadius,
                viewModel.enableRdapEnrichment,
                viewModel.enableIpRangeCounts
            )
        } catch (e: Exception) {
            Log.e(TAG, "searchIpRanges error", e)
            Snackbar.make(
                binding.root,
                getString(R.string.search_error, e.message),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun getColorForDatabase(databaseId: String): Int {
        return viewModel.getColorForDatabase(databaseId)
    }

    private fun showNetworkInfo(point: NetworkPoint) {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val dialogView = layoutInflater.inflate(R.layout.dialog_network_details, null)

        val macAddress = viewModel.convertBssidToString(point.bssidDecimal)
        val database = viewModel.availableDatabases.value?.find { it.id == point.databaseId }
        val databaseName =
            database?.let { formatSourcePath(it.path) } ?: getString(R.string.unknown_database)
        val dbColor = point.color.takeIf { it != 0 } ?: getColorForDatabase(point.databaseId)

        dialogView.apply {
            findViewById<TextView>(R.id.textViewBssid).text = macAddress

            findViewById<View>(R.id.databaseColorCircle).setBackgroundColor(dbColor)
            findViewById<TextView>(R.id.textViewDatabase).text = databaseName
            findViewById<TextView>(R.id.textViewRecordCount).text =
                getString(R.string.multiple_records, point.allRecords.size)

            val textViewCoordinates = findViewById<TextView>(R.id.textViewCoordinates)
            textViewCoordinates.text =
                String.format(java.util.Locale.US, "%.4f   %.4f", point.latitude, point.longitude)

            val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewRecords)
            recyclerView.layoutManager = LinearLayoutManager(ctx)
            val records = if (point.allRecords.isNotEmpty()) {
                point.allRecords.map { record ->
                    record.copy(
                        databaseColor = dbColor,
                        databaseName = databaseName
                    )
                }
            } else {
                listOf(
                    NetworkRecord(
                        essid = point.essid ?: getString(R.string.unknown_ssid),
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
                            "bssid" to macAddress,
                            "essid" to point.essid,
                            "password" to point.password
                        ),
                        databaseColor = dbColor,
                        databaseName = databaseName
                    )
                )
            }

            val adapter = NetworkRecordsAdapter(
                records,
                ctx,
                macAddress,
                { record ->
                    saveRecordToLocalDb(ctx, record, point.latitude, point.longitude, macAddress)
                },
                viewLifecycleOwner.lifecycleScope
            )
            recyclerView.adapter = adapter

            findViewById<ImageButton>(R.id.buttonCopyCoordinates).setOnClickListener {
                val coordinates = "${point.latitude}, ${point.longitude}"
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(getString(R.string.coordinates), coordinates)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(ctx, getString(R.string.coordinates_copied), Toast.LENGTH_SHORT)
                    .show()
            }

            findViewById<ImageButton>(R.id.buttonOpenMap).setOnClickListener {
                val geoUri =
                    "geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}(${point.essid ?: macAddress})"
                val uri = android.net.Uri.parse(geoUri)
                val pm = ctx.packageManager

                val googleMapsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                }

                var opened = false

                if (googleMapsIntent.resolveActivity(pm) != null) {
                    try {
                        startActivity(googleMapsIntent)
                        opened = true
                    } catch (e: Exception) {
                    }
                }

                if (!opened) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                    val chooserIntent = Intent.createChooser(fallbackIntent, null)
                    if (chooserIntent.resolveActivity(pm) != null) {
                        try {
                            startActivity(chooserIntent)
                            opened = true
                        } catch (e: Exception) {
                        }
                    }
                }

                if (!opened) {
                    Toast.makeText(ctx, getString(R.string.no_map_app_found), Toast.LENGTH_SHORT)
                        .show()
                }
            }

            findViewById<ImageButton>(R.id.buttonCreateQr).setOnClickListener {
                val firstValidRecord = point.allRecords.firstOrNull { !it.password.isNullOrBlank() }
                if (firstValidRecord != null) {
                    adapter.showQrForRecord(firstValidRecord)
                } else {
                    Toast.makeText(
                        ctx,
                        getString(R.string.password_not_available),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            findViewById<ImageButton>(R.id.buttonShareData).setOnClickListener {
                val firstRecord = point.allRecords.firstOrNull()
                val shareText = buildString {
                    append("Network: ${firstRecord?.essid ?: getString(R.string.unknown_ssid)}\n")
                    append("BSSID: $macAddress\n")
                    append("Password: ${firstRecord?.password ?: getString(R.string.not_available)}")
                    if (!firstRecord?.wpsPin.isNullOrBlank()) {
                        append("\nWPS PIN: ${firstRecord?.wpsPin}")
                    }
                }

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                startActivity(
                    Intent.createChooser(
                        shareIntent,
                        getString(R.string.share_network_data)
                    )
                )
            }
        }

        dialog.setContentView(dialogView)
        dialog.behavior?.state =
            BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun saveRecordToLocalDb(
        ctx: Context,
        record: NetworkRecord,
        latitude: Double,
        longitude: Double,
        defaultBssid: String
    ) {
        val adminPanelText = record.adminCredentials.joinToString(";") {
            "${it.login}:${it.password}"
        }.ifEmpty { null }

        val bssidStr = (record.rawData["bssid"] as? String)
            ?: (record.rawData["BSSID"] as? String)
            ?: (record.rawData["macAddress"] as? String)
            ?: defaultBssid
        val essidStr = record.essid ?: ""

        if (bssidStr.isBlank() && essidStr.isBlank()) {
            Toast.makeText(ctx, getString(R.string.no_data_to_save), Toast.LENGTH_SHORT).show()
            return
        }

        val wifiNetwork = com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork(
            id = 0L,
            wifiName = essidStr,
            macAddress = bssidStr,
            wifiPassword = record.password,
            wpsCode = record.wpsPin,
            adminPanel = adminPanelText,
            latitude = latitude,
            longitude = longitude
        )

        val savedMessage = getString(R.string.saved_to_local_db)
        val failedMessage = getString(R.string.operation_failed)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                val helper = com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper(ctx)
                try {
                    helper.addRecord(wifiNetwork) != -1L
                } finally {
                    helper.close()
                }
            } catch (e: Exception) {
                com.lsd.wififrankenstein.util.Log.e("WiFiMapFragment", "Failed to save record", e)
                false
            }

            val message = if (success) savedMessage else failedMessage
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatSourcePath(path: String): String {
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

                else -> {
                    path.substringAfterLast('/')
                }
            }.substringAfterLast("%2F")
        } catch (e: Exception) {
            path
        }
    }


    private fun clearMarkers() {
        if (_binding == null) return

        canvasOverlay.updatePoints(emptyList())
        binding.map.postInvalidate()
    }

    override fun onStart() {
        super.onStart()
        isUserInteracting = false
        lastInteractionTime = 0L
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()

        if (hasLocationPermission()) {
            userLocationManager.startLocationUpdates()
        }

        isUserInteracting = false
        lastInteractionTime = 0L
        lastMapUpdateTime = 0L
        lastUpdateZoom = -1.0
        lastUpdateCenter = null
        lastClusterUpdateZoom = -1.0
        lastClusterUpdateCenter = null
        updateJob?.cancel()
        updateJob = null

        isClustersPreventMerged = viewModel.getPreventClusterMerge()
        updateFabIcon()

        checkDatabaseValidity()
        updateDatabaseLegend()

        if (selectedDatabases.isNotEmpty()) {
            clearMarkers()
            viewLifecycleOwner.lifecycleScope.launch {
                delay(300)
                updateVisiblePoints()
            }
        }

        if (DbSetupViewModel.needDataRefresh) {
            lifecycleScope.launch {
                DbSetupViewModel.needDataRefresh = false

                viewModel.reloadAvailableDatabases()

                Toast.makeText(
                    requireContext(),
                    getString(R.string.databases_list_refreshed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    private fun checkDatabaseValidity() {
        val availableDatabases = viewModel.availableDatabases.value ?: emptyList()
        val availableIds = availableDatabases.map { it.id }.toSet()

        val invalidDatabases = selectedDatabases.filterNot { availableIds.contains(it.id) }

        if (invalidDatabases.isNotEmpty()) {
            selectedDatabases.removeAll(invalidDatabases)
            syncSelectedDatabaseIds()
            databaseAdapter.notifyDataSetChanged()

            clearMarkers()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        userLocationManager.stopLocationUpdates()

        isUserInteracting = false
        updateJob?.cancel()
    }

    private fun syncSelectedDatabaseIds() {
        viewModel.setSelectedDatabaseIds(selectedDatabases.map { it.id }.toSet())
    }

    override fun onDestroyView() {
        super.onDestroyView()

        updateJob?.cancel()
        updateJob = null
        interactionTimer?.cancel()
        interactionTimer = null
        restoreJob?.cancel()
        restoreJob = null
        offlineEstimateJob?.cancel()
        offlineEstimateJob = null
        isUserInteracting = false

        radiusCircleOverlay?.let { overlay ->
            if (_binding != null) {
                binding.map.overlays.remove(overlay)
            }
            radiusCircleOverlay = null
        }
        userLocationMarker?.let { marker ->
            if (_binding != null) {
                binding.map.overlays.remove(marker)
            }
            userLocationMarker = null
        }
        if (_binding != null) {
            binding.map.overlays.remove(canvasOverlay)
        }

        userLocationManager.onDestroy()
        _binding = null
    }

    private fun updateDatabaseLegend() {
        if (selectedDatabases.isEmpty()) {
            return
        }
        databaseAdapter.notifyDataSetChanged()
    }

    private fun updateIpRangesDisplay(ranges: List<com.lsd.wififrankenstein.ui.ipranges.IpRangeResult>) {
        Log.d(TAG, "updateIpRangesDisplay: ${ranges.size} ranges received")
        val ctx = context ?: return
        val recyclerView = binding.ipRangesRecyclerView

        if (ranges.isEmpty()) {
            recyclerView.visibility = View.GONE
            binding.ipRangesActions.visibility = View.GONE
            binding.ipRangesActionsTop.visibility = View.GONE
            selectedIpRanges.clear()
            Log.d(TAG, "updateIpRangesDisplay: no ranges found")
            return
        }

        recyclerView.visibility = View.VISIBLE
        binding.ipRangesActions.visibility = View.VISIBLE
        binding.ipRangesActionsTop.visibility = View.VISIBLE
        selectedIpRanges.clear()
        binding.buttonCopySelected.isEnabled = false
        binding.buttonCopySelectedTop.isEnabled = false
        Log.d(TAG, "updateIpRangesDisplay: showing ${ranges.size} ranges")

        if (ipRangesLayoutManager == null) {
            ipRangesLayoutManager = LinearLayoutManager(ctx)
            recyclerView.layoutManager = ipRangesLayoutManager
        }

        if (ipRangesAdapter == null) {
            ipRangesAdapter = SimpleIpRangesAdapter(
                ctx,
                onCopyClick = { result ->
                    val clipboard =
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(ctx.getString(R.string.ip_range), result.range)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.copied, result.range),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onSelectionChanged = { selectedCount ->
                    binding.buttonCopySelected.isEnabled = selectedCount > 0
                    binding.buttonCopySelectedTop.isEnabled = selectedCount > 0
                }
            )
            recyclerView.adapter = ipRangesAdapter
        }

        ipRangesAdapter!!.updateData(ranges)
    }

    private fun copyAllIpRanges() {
        val adapter = ipRangesAdapter ?: return
        val ctx = context ?: return
        val allRanges = adapter.getAllRanges()
        val text = allRanges.joinToString("\n") { it.range }
        if (text.isEmpty()) return
        Log.d(TAG, "copyAllIpRanges: ${allRanges.size} ranges:\n$text")
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(ctx.getString(R.string.ip_ranges_label), text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(
            ctx,
            ctx.getString(R.string.ranges_copied, allRanges.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun copySelectedIpRanges() {
        val adapter = ipRangesAdapter ?: return
        val ctx = context ?: return
        val selected = adapter.getSelectedRanges()
        if (selected.isEmpty()) return
        val text = selected.joinToString("\n") { it.range }
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(ctx.getString(R.string.ip_ranges_label), text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(
            ctx,
            ctx.getString(R.string.ranges_copied, selected.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    private class SimpleIpRangesAdapter(
        private val context: Context,
        private val onCopyClick: (com.lsd.wififrankenstein.ui.ipranges.IpRangeResult) -> Unit,
        private val onSelectionChanged: (selectedCount: Int) -> Unit = {}
    ) : RecyclerView.Adapter<SimpleIpRangesAdapter.ViewHolder>() {

        private val ranges = mutableListOf<com.lsd.wififrankenstein.ui.ipranges.IpRangeResult>()
        private val selectedPositions = mutableSetOf<Int>()

        fun updateData(newRanges: List<com.lsd.wififrankenstein.ui.ipranges.IpRangeResult>) {
            ranges.clear()
            ranges.addAll(newRanges)
            selectedPositions.clear()
            notifyDataSetChanged()
        }

        fun getAllRanges(): List<com.lsd.wififrankenstein.ui.ipranges.IpRangeResult> =
            ranges.toList()

        fun getSelectedRanges(): List<com.lsd.wififrankenstein.ui.ipranges.IpRangeResult> {
            return selectedPositions.map { ranges[it] }
        }

        private fun toggleSelection(position: Int) {
            if (selectedPositions.contains(position)) {
                selectedPositions.remove(position)
            } else {
                selectedPositions.add(position)
            }
            notifyItemChanged(position)
            onSelectionChanged(selectedPositions.size)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val rangeText: TextView = view.findViewById(R.id.rangeText)
            val sourceText: TextView = view.findViewById(R.id.sourceText)
            val countText: TextView = view.findViewById(R.id.countText)
            val descriptionText: TextView = view.findViewById(R.id.descriptionText)
            val netnameText: TextView = view.findViewById(R.id.netnameText)
            val countryText: TextView = view.findViewById(R.id.countryText)
            val copyButton: com.google.android.material.button.MaterialButton =
                view.findViewById(R.id.copyButton)
            val colorDot: View = view.findViewById(R.id.colorDot)
            val selectionCheckbox: com.google.android.material.checkbox.MaterialCheckBox =
                view.findViewById(R.id.selectionCheckbox)

            init {
                copyButton.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onCopyClick(ranges[position])
                    }
                }
                selectionCheckbox.setOnCheckedChangeListener(null)
                selectionCheckbox.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        toggleSelection(position)
                    }
                }
                itemView.setOnClickListener {
                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        selectionCheckbox.performClick()
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = com.lsd.wififrankenstein.databinding.ItemIpRangeBinding.inflate(
                LayoutInflater.from(context), parent, false
            )
            return ViewHolder(binding.root)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val range = ranges[position]
            holder.rangeText.text = range.range
            holder.sourceText.text = range.sourceName

            if (range.pointCount > 0) {
                holder.countText.text =
                    context.getString(R.string.ip_range_points, range.pointCount)
                holder.countText.visibility = View.VISIBLE
            } else {
                holder.countText.visibility = View.GONE
            }

            val isSelected = selectedPositions.contains(position)
            holder.selectionCheckbox.isChecked = isSelected

            if (range.databaseColor != 0) {
                holder.colorDot.visibility = View.VISIBLE
                holder.colorDot.setBackgroundColor(range.databaseColor)
            } else {
                holder.colorDot.visibility = View.GONE
            }

            if (range.description.isNotEmpty()) {
                holder.descriptionText.text = range.description
                holder.descriptionText.visibility = View.VISIBLE
            } else {
                holder.descriptionText.visibility = View.GONE
            }

            if (range.netname.isNotEmpty()) {
                holder.netnameText.text = range.netname
                holder.netnameText.visibility = View.VISIBLE
            } else {
                holder.netnameText.visibility = View.GONE
            }

            if (range.country.isNotEmpty()) {
                holder.countryText.text = context.getString(R.string.country_label, range.country)
                holder.countryText.visibility = View.VISIBLE
            } else {
                holder.countryText.visibility = View.GONE
            }
        }

        override fun getItemCount() = ranges.size
    }

    private class OfflineZoneAdapter(
        private val zones: List<OfflineMapManager.OfflineZone>,
        private val onDelete: (OfflineMapManager.OfflineZone) -> Unit
    ) : RecyclerView.Adapter<OfflineZoneAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.zoneNameText)
            val infoText: TextView = view.findViewById(R.id.zoneInfoText)
            val deleteButton: com.google.android.material.button.MaterialButton =
                view.findViewById(R.id.zoneDeleteButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_offline_zone, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val zone = zones[position]
            holder.nameText.text = zone.name
            val ctx = holder.itemView.context
            val sizeStr = when {
                zone.sizeBytes < 1024 -> "${zone.sizeBytes} B"
                zone.sizeBytes < 1024 * 1024 -> "${zone.sizeBytes / 1024} KB"
                else -> "%.1f MB".format(java.util.Locale.US, zone.sizeBytes / (1024.0 * 1024.0))
            }
            holder.infoText.text = ctx.getString(R.string.wm_tiles_info, zone.tileCount, sizeStr)
            holder.deleteButton.setOnClickListener { onDelete(zone) }
        }

        override fun getItemCount() = zones.size
    }

}