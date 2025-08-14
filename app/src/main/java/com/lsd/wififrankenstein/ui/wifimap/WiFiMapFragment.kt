package com.lsd.wififrankenstein.ui.wifimap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import com.lsd.wififrankenstein.util.Log
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
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentWifiMapBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel
import com.lsd.wififrankenstein.util.CompatibilityHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker

class WiFiMapFragment : Fragment() {
    private val TAG = "WiFiMapFragment"

    private var _binding: FragmentWifiMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WiFiMapViewModel by viewModels()
    private lateinit var databaseAdapter: MapDatabaseAdapter
    private lateinit var legendAdapter: MapLegendAdapter
    private val selectedDatabases = mutableSetOf<DbItem>()
    private val markers = mutableListOf<Marker>()
    private var isDatabasesExpanded = true
    private var isLegendExpanded = true
    private var updateJob: Job? = null
    private var isClustersPreventMerged = false
    private lateinit var canvasOverlay: MapCanvasOverlay

    private lateinit var userLocationManager: UserLocationManager
    private var userLocationMarker: Marker? = null

    private var currentIndexingDb: DbItem? = null

    private var nextColorIndex = 0

    private var lastMapUpdateTime = 0L
    private val MAP_UPDATE_DEBOUNCE_MS = 200L
    private var lastUpdateZoom = -1.0
    private var lastUpdateCenter: GeoPoint? = null
    private var lastClusterUpdateZoom = -1.0
    private var lastClusterUpdateCenter: GeoPoint? = null

    private var isUserInteracting = false
    private var interactionEndTime = 0L
    private val INTERACTION_COOLDOWN_MS = 300L

    private val settingsViewModel: SettingsViewModel by viewModels()

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

        Configuration.getInstance().load(
            requireContext(),
            PreferenceManager.getDefaultSharedPreferences(requireContext())
        )

        setupMap()
        setupRecyclerView()
        setupLegend()
        setupCollapsibleCards()
        observeViewModel()
        setupToggleClusterButton()
        setupLocationButton()
        setupUserLocation()

        return binding.root
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

            clearMarkers()
            scheduleMapUpdate(true)
        }
    }

    private fun updateFabIcon() {
        if (isClustersPreventMerged) {
            binding.fabToggleClusters.setImageResource(R.drawable.ic_layers)
            binding.fabToggleClusters.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.green_500))
        } else {
            binding.fabToggleClusters.setImageResource(R.drawable.ic_layers)
            binding.fabToggleClusters.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.blue_500))
        }
    }

    private fun setupMap() {
        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setMultiTouchControls(true)

            addOnFirstLayoutListener { _, _, _, _, _ ->
                controller.setZoom(DEFAULT_ZOOM)
                controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LON))
            }

            canvasOverlay = MapCanvasOverlay { point ->
                if (point.bssidDecimal == -1L) {
                    controller.animateTo(
                        GeoPoint(point.latitude, point.longitude),
                        zoomLevelDouble + 1.0,
                        400L
                    )
                } else {
                    if (point.isDataLoaded) {
                        showNetworkInfo(point)
                    } else {
                        lifecycleScope.launch {
                            viewModel.loadPointInfo(point)
                        }
                    }
                }
            }

            overlays.add(canvasOverlay)

            var interactionTimer: Job? = null

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent): Boolean {
                    isUserInteracting = true
                    interactionTimer?.cancel()

                    interactionTimer = lifecycleScope.launch {
                        delay(500)
                        isUserInteracting = false
                        interactionEndTime = System.currentTimeMillis()

                        delay(INTERACTION_COOLDOWN_MS)
                        scheduleMapUpdate()
                    }
                    return true
                }

                override fun onZoom(event: ZoomEvent): Boolean {
                    isUserInteracting = true
                    interactionTimer?.cancel()

                    interactionTimer = lifecycleScope.launch {
                        delay(800)
                        isUserInteracting = false
                        interactionEndTime = System.currentTimeMillis()

                        delay(INTERACTION_COOLDOWN_MS)
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

        Log.d(TAG, "scheduleMapUpdate called: forceUpdate=$forceUpdate, isUserInteracting=$isUserInteracting")

        if (!forceUpdate) {
            if (isUserInteracting) {
                Log.d(TAG, "User is interacting, postponing update")
                return
            }

            if (currentTime - interactionEndTime < INTERACTION_COOLDOWN_MS && interactionEndTime > 0) {
                Log.d(TAG, "Still in interaction cooldown: ${currentTime - interactionEndTime}ms < ${INTERACTION_COOLDOWN_MS}ms")
                return
            }

            if (!shouldUpdateClusters(currentZoom, currentCenter)) {
                Log.d(TAG, "Skipping cluster update - insufficient zoom/position change")
                return
            }
        }

        updateJob?.cancel()
        lastMapUpdateTime = currentTime

        updateJob = lifecycleScope.launch {
            if (!forceUpdate) {
                delay(MAP_UPDATE_DEBOUNCE_MS)
            }

            if ((lastMapUpdateTime == currentTime || forceUpdate) && !isUserInteracting) {
                Log.d(TAG, "Executing map update")
                updateVisiblePoints()
            } else {
                Log.d(TAG, "Map update cancelled: userInteracting=$isUserInteracting, timeMatch=${lastMapUpdateTime == currentTime}")
            }
        }
    }

    private fun shouldUpdateClusters(currentZoom: Double, currentCenter: GeoPoint?): Boolean {
        if (currentCenter == null) {
            Log.d(TAG, "No current center, allowing update")
            return true
        }

        if (lastClusterUpdateZoom < 0) {
            Log.d(TAG, "First cluster update, allowing")
            lastClusterUpdateZoom = currentZoom
            lastClusterUpdateCenter = currentCenter
            return true
        }

        val zoomDiff = kotlin.math.abs(currentZoom - lastClusterUpdateZoom)
        Log.d(TAG, "Zoom diff: $zoomDiff (current: $currentZoom, last: $lastClusterUpdateZoom)")

        if (zoomDiff >= 0.1) {
            Log.d(TAG, "Zoom changed significantly ($zoomDiff), allowing update")
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
            currentZoom >= 18.0 -> viewportDiagonal * 0.05
            currentZoom >= 16.0 -> viewportDiagonal * 0.08
            currentZoom >= 14.0 -> viewportDiagonal * 0.12
            currentZoom >= 12.0 -> viewportDiagonal * 0.15
            currentZoom >= 10.0 -> viewportDiagonal * 0.18
            else -> viewportDiagonal * 0.2
        }

        val shouldUpdate = centerDistance > movementThreshold

        Log.d(TAG, "Movement check: distance=$centerDistance, threshold=$movementThreshold, shouldUpdate=$shouldUpdate")

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
                    binding.textViewProgress.visibility = View.VISIBLE
                    binding.textViewProgress.text = getString(R.string.refreshing_map_data)
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.startAnimation()

                    viewModel.forceRefresh()
                    clearMarkers()
                    resetMapState()
                    updateLegend()

                    lifecycleScope.launch {
                        delay(100)
                        scheduleMapUpdate(true)
                        delay(500)
                        if (selectedDatabases.isNotEmpty()) {
                            Snackbar.make(binding.root, getString(R.string.map_data_refreshed), Snackbar.LENGTH_SHORT).show()
                        }
                    }
                },
                viewModel
            )
            adapter = databaseAdapter
        }

        viewModel.availableDatabases.observe(viewLifecycleOwner) { databases ->
            val filteredDatabases = databases.filter { it.dbType != DbType.WIFI_API }
            databaseAdapter = MapDatabaseAdapter(
                filteredDatabases,
                selectedDatabases,
                {
                    binding.textViewProgress.visibility = View.VISIBLE
                    binding.textViewProgress.text = getString(R.string.refreshing_map_data)
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.startAnimation()

                    viewModel.forceRefresh()
                    clearMarkers()
                    resetMapState()

                    lifecycleScope.launch {
                        delay(100)
                        scheduleMapUpdate(true)
                        scheduleMapUpdate()
                        delay(500)
                        if (selectedDatabases.isNotEmpty()) {
                            Snackbar.make(binding.root, getString(R.string.map_data_refreshed), Snackbar.LENGTH_SHORT).show()
                        }
                    }
                },
                viewModel
            )
            binding.databasesRecyclerView.adapter = databaseAdapter
        }
    }

    private fun setupLocationButton() {
        Log.d(TAG, "Setting up location button")

        binding.fabLocation.setOnClickListener {
            Log.d(TAG, "Location button clicked")

            userLocationMarker?.let { marker ->
                Log.d(TAG, "Moving to existing location marker")
                binding.map.controller.animateTo(marker.position, 18.0, 400L)
            } ?: run {
                Log.d(TAG, "No existing location marker, requesting new location")
                Snackbar.make(binding.root, getString(R.string.location_requested), Snackbar.LENGTH_SHORT).show()
                userLocationManager.requestSingleLocationUpdate()
            }
        }

        Log.d(TAG, "Location button setup complete")
    }

    private fun setupUserLocation() {
        Log.d(TAG, "Setting up user location manager")

        userLocationManager = UserLocationManager(requireContext())

        userLocationManager.userLocation.observe(viewLifecycleOwner) { location ->
            Log.d(TAG, "User location received: $location")
            location?.let {
                updateUserLocationMarker(it)

                if (userLocationMarker == null) {
                    Log.d(TAG, "First location received, animating to position")
                    binding.map.controller.animateTo(it, 18.0, 400L)
                }
            }
        }

        userLocationManager.locationError.observe(viewLifecycleOwner) { errorKey ->
            Log.e(TAG, "Location error: $errorKey")
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

        Log.d(TAG, "User location manager setup complete")
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
                    userLocationManager.startLocationUpdates()
                } else {
                    showLocationPermissionDialog()
                }
            }
        }
    }

    private fun showLocationPermissionDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.location_permission_required)
            .setMessage(R.string.location_permission_explanation)
            .setPositiveButton(R.string.settings) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = android.net.Uri.fromParts("package", requireContext().packageName, null)
        startActivity(intent)
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
        Log.d(TAG, "Showing create indexes dialog for ${dbItem.id}")
        currentIndexingDb = dbItem

        val indexLevels = arrayOf(
            getString(R.string.index_level_full_option),
            getString(R.string.index_level_basic_option),
            getString(R.string.index_level_none_option)
        )

        var selectedLevel = 1

        MaterialAlertDialogBuilder(requireContext())
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
                    Log.d(TAG, "User chose no indexing for ${dbItem.id}")
                    selectedDatabases.remove(dbItem)
                    databaseAdapter.notifyDataSetChanged()
                    currentIndexingDb = null
                    return@setPositiveButton
                }

                Log.d(TAG, "User chose to create $level indexes for ${dbItem.id}")
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
                databaseAdapter.notifyDataSetChanged()
                currentIndexingDb = null
            }
            .setCancelable(false)
            .show()
    }

    private var indexingDialog: AlertDialog? = null

    private fun showIndexingProgress() {
        Log.d(TAG, "Showing indexing progress dialog")
        indexingDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.creating_indexes)
            .setView(R.layout.dialog_indexing_progress)
            .setCancelable(false)
            .show()
    }

    private fun updateIndexingProgress(progress: Int) {
        Log.d(TAG, "Updating indexing progress: $progress%")
        val progressBar = indexingDialog?.findViewById<ProgressBar>(R.id.progressBar)
        if (progressBar != null) {
            progressBar.progress = progress
            Log.d(TAG, "Updated progress bar to $progress%")
        } else {
            Log.e(TAG, "Could not find progress bar in dialog")
        }

        if (progress >= 100) {
            Log.d(TAG, "Indexing complete, dismissing dialog")
            indexingDialog?.dismiss()

            currentIndexingDb?.let { dbItem ->
                Log.d(TAG, "Adding indexed database ${dbItem.id} to selected databases")
                selectedDatabases.add(dbItem)
                databaseAdapter.notifyDataSetChanged()

                clearMarkers()
                scheduleMapUpdate(true)
            } ?: Log.e(TAG, "currentIndexingDb is null after indexing completion")

            currentIndexingDb = null
        }
    }

    private fun setupLegend() {
        binding.legendRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            legendAdapter = MapLegendAdapter()
            adapter = legendAdapter
        }
    }

    private fun resetMapState() {
        lastMapUpdateTime = 0L
        lastUpdateZoom = -1.0
        lastUpdateCenter = null
        lastClusterUpdateZoom = -1.0
        lastClusterUpdateCenter = null

        updateJob?.cancel()
        updateJob = null
    }

    private fun observeViewModel() {
        viewModel.loadingProgress.observe(viewLifecycleOwner) { count ->
            Log.d(TAG, "Loading progress updated: $count")
            when {
                count < 100 -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.startAnimation()
                    binding.textViewProgress.visibility = View.VISIBLE
                    binding.textViewProgress.text = when {
                        count < 50 -> getString(R.string.loading_points_progress)
                        count < 75 -> getString(R.string.clustering_points)
                        else -> getString(R.string.rendering_markers)
                    }
                }
                else -> {
                    binding.progressBar.stopAnimation()
                    binding.textViewProgress.visibility = View.GONE
                }
            }
        }

        settingsViewModel.mapSettingsChanged.observe(viewLifecycleOwner) { changed ->
            if (changed) {
                viewModel.updateClusterManager()
                clearMarkers()
                scheduleMapUpdate(true)
                settingsViewModel.resetMapSettingsChangedFlag()

                Snackbar.make(binding.root, getString(R.string.cluster_settings_updated), Snackbar.LENGTH_SHORT).show()
            }
        }

        settingsViewModel.clusterSettingsChanged.observe(viewLifecycleOwner) { changed ->
            if (changed) {
                viewModel.updateClusterSettings()
                clearMarkers()
                scheduleMapUpdate(true)
                settingsViewModel.resetClusterSettingsChangedFlag()
            }
        }

        viewModel.databaseColors.observe(viewLifecycleOwner) { colors ->
            if (selectedDatabases.isNotEmpty()) {
                updateLegend()
            }
        }

        viewModel.points.observe(viewLifecycleOwner) { points ->
            Log.d(TAG, "Received ${points.size} points from ViewModel")
            updateMarkers(points)
            binding.loadingIndicator.visibility = View.GONE
            binding.textViewProgress.visibility = View.GONE
            updateLegend()
        }

        viewModel.addReadOnlyDb.observe(viewLifecycleOwner) { dbItem ->
            dbItem?.let {
                selectedDatabases.add(it)
                databaseAdapter.notifyDataSetChanged()

                viewModel.clearCache()
                clearMarkers()
                resetMapState()
                updateLegend()
                scheduleMapUpdate(true)

                Log.d(TAG, "Added read-only database and refreshed map")
            }
        }

        viewModel.selectedPoint.observe(viewLifecycleOwner) { point ->
            if (point.isDataLoaded) {
                showNetworkInfo(point)
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            Log.e(TAG, "Error received: $error")
            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
        }

        viewModel.showIndexingDialog.observe(viewLifecycleOwner) { dbItem ->
            dbItem?.let { showCreateIndexesDialog(it) }
        }

        viewModel.indexingProgress.observe(viewLifecycleOwner) { progress ->
            updateIndexingProgress(progress)
        }
    }

    private fun updateVisiblePoints() {
        Log.d(TAG, "updateVisiblePoints called")

        val zoom = binding.map.zoomLevelDouble
        lastUpdateZoom = zoom
        lastUpdateCenter = binding.map.mapCenter as? GeoPoint
        val minZoom = viewModel.getMinZoomForMarkers()
        val boundingBox = binding.map.boundingBox

        Log.d(TAG, "=== MAP UPDATE DEBUG ===")
        Log.d(TAG, "Current zoom: $zoom")
        Log.d(TAG, "Min zoom for markers: $minZoom")
        Log.d(TAG, "Bounding box: $boundingBox")
        Log.d(TAG, "Selected databases: ${selectedDatabases.size}")

        if (selectedDatabases.isEmpty()) {
            Log.d(TAG, "No databases selected, clearing everything")
            clearMarkers()
            binding.progressBar.stopAnimation()
            binding.textViewProgress.text = getString(R.string.select_database_message)
            val typedValue = android.util.TypedValue()
            requireContext().theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            binding.textViewProgress.setTextColor(typedValue.data)
            binding.textViewProgress.setTypeface(null, android.graphics.Typeface.NORMAL)
            binding.textViewProgress.visibility = View.VISIBLE
            return
        }

        if (zoom < minZoom) {
            Log.d(TAG, "Zoom too low: $zoom < $minZoom, showing zoom message")
            clearMarkers()
            binding.progressBar.stopAnimation()
            binding.textViewProgress.text = getString(R.string.zoom_in_message)
            binding.textViewProgress.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            binding.textViewProgress.setTypeface(null, android.graphics.Typeface.BOLD)
            binding.textViewProgress.visibility = View.VISIBLE
            return
        }

        Log.d(TAG, "Zoom check passed, proceeding with update")

        binding.textViewProgress.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.startAnimation()

        if (boundingBox == null) {
            Log.d(TAG, "Bounding box is null")
            return
        }

        if (!CompatibilityHelper.canHandleLargeFiles() && selectedDatabases.size > 2) {
            binding.textViewProgress.text = getString(R.string.too_many_databases_selected)
            binding.textViewProgress.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            binding.textViewProgress.visibility = View.VISIBLE
            binding.progressBar.stopAnimation()
            return
        }

        updateJob?.cancel()

        updateJob = lifecycleScope.launch {
            try {
                val currentColors = selectedDatabases.associate { database ->
                    database.id to getColorForDatabase(database.id)
                }

                Log.d(TAG, "Starting loadPointsInBoundingBox")
                viewModel.loadPointsInBoundingBox(
                    boundingBox,
                    zoom,
                    selectedDatabases
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in updateVisiblePoints", e)
                binding.progressBar.stopAnimation()
                binding.textViewProgress.text = getString(R.string.map_loading_error)
                binding.textViewProgress.visibility = View.VISIBLE
            }
        }
    }

    private fun updateMarkers(visiblePoints: List<NetworkPoint>) {
        Log.d(TAG, "Updating canvas overlay with ${visiblePoints.size} visible points")

        val clusterSizes = visiblePoints.groupBy {
            if (it.bssidDecimal == -1L) it.essid?.substringBetween("(", " points)")?.toIntOrNull() ?: 1 else 1
        }.mapValues { it.value.size }
        Log.d(TAG, "Cluster size distribution: $clusterSizes")

        lifecycleScope.launch(Dispatchers.Main) {
            canvasOverlay.updatePoints(visiblePoints)
            binding.map.postInvalidate()
            updateLegend()

            binding.progressBar.stopAnimation()
            binding.textViewProgress.visibility = View.GONE
        }
    }

    private fun setupCollapsibleCards() {
        binding.databasesCollapseButton.setOnClickListener {
            isDatabasesExpanded = !isDatabasesExpanded
            binding.databasesRecyclerView.visibility = if (isDatabasesExpanded) View.VISIBLE else View.GONE
            binding.databasesCollapseButton.setImageResource(
                if (isDatabasesExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
            binding.databasesCollapseButton.contentDescription = getString(
                if (isDatabasesExpanded) R.string.collapse else R.string.expand
            )
        }

        binding.legendCollapseButton.setOnClickListener {
            isLegendExpanded = !isLegendExpanded
            binding.legendRecyclerView.visibility = if (isLegendExpanded) View.VISIBLE else View.GONE
            binding.legendCollapseButton.setImageResource(
                if (isLegendExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
            binding.legendCollapseButton.contentDescription = getString(
                if (isLegendExpanded) R.string.collapse else R.string.expand
            )
        }
    }

    private fun addMarkerForPoint(point: NetworkPoint) {
        try {
            val marker = Marker(binding.map).apply {
                position = GeoPoint(point.displayLatitude, point.displayLongitude)

                if (point.bssidDecimal == -1L) {
                    title = point.essid
                    icon = getClusterIcon(point)
                    setOnMarkerClickListener { _, _ ->
                        binding.map.controller.animateTo(
                            position,
                            binding.map.zoomLevelDouble + 1.0,
                            400L
                        )
                        true
                    }
                } else {
                    snippet = point.bssidDecimal.toString()
                    title = point.bssidDecimal.toString()
                    icon = getMarkerIcon()?.apply {
                        setTint(getColorForDatabase(point.databaseId))
                    }
                    setOnMarkerClickListener { _, _ ->
                        if (point.isDataLoaded) {
                            showNetworkInfo(point)
                        } else {
                            lifecycleScope.launch {
                                viewModel.loadPointInfo(point)
                            }
                        }
                        true
                    }
                }
            }

            markers.add(marker)
            binding.map.overlays.add(marker)

        } catch (e: Exception) {
            Log.e(TAG, "Error adding marker for point with decimal BSSID: ${point.bssidDecimal}", e)
        }
    }

    private fun getClusterIcon(point: NetworkPoint): Drawable {
        val countText = point.essid?.substringBetween("(", " points)") ?: "0"
        val count = countText.toIntOrNull() ?: 0

        val isMultiPointCluster = count > 1

        val baseSize = if (isMultiPointCluster) 280 else 120
        val size = if (isMultiPointCluster) {
            when {
                count >= 100000 -> (baseSize * 3).toInt()
                count >= 50000 -> (baseSize * 2.5).toInt()
                count >= 20000 -> (baseSize * 2.25).toInt()
                count >= 10000 -> (baseSize * 2).toInt()
                count >= 5000 -> (baseSize * 1.75).toInt()
                count >= 2000 -> (baseSize * 1.5).toInt()
                count >= 1000 -> (baseSize * 1.25).toInt()
                else -> baseSize
            }
        } else {
            120
        }

        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            color = getColorForDatabase(point.databaseId)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.apply {
            color = Color.WHITE
            textSize = if (isMultiPointCluster) size.toFloat() / 4f else size.toFloat() / 3f
            textAlign = Paint.Align.CENTER
        }

        val displayText = if (isMultiPointCluster) {
            when {
                count >= 1000000 -> "${count / 1000000}M"
                count >= 1000 -> "${count / 1000}k"
                else -> countText
            }
        } else {
            "1"
        }

        canvas.drawText(displayText, size.toFloat() / 2f, size.toFloat() / 2f + 8f, paint)

        return bitmap.toDrawable(binding.root.resources)
    }

    private fun String.substringBetween(start: String, end: String): String {
        val startIndex = this.indexOf(start) + start.length
        val endIndex = this.indexOf(end)
        return if (startIndex != -1 && endIndex != -1) {
            this.substring(startIndex, endIndex)
        } else {
            "0"
        }
    }

    private fun getMarkerIcon(): Drawable? {
        return try {
            resources.getDrawable(R.drawable.ic_marker_default, requireContext().theme)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading marker icon", e)
            null
        }
    }

    private fun getColorForDatabase(databaseId: String): Int {
        return viewModel.getColorForDatabase(databaseId)
    }

    private fun updateLegend() {
        if (selectedDatabases.isEmpty()) {
            binding.legendCard.visibility = View.GONE
            return
        }

        val legendItems = selectedDatabases.map { database ->
            Pair(database, viewModel.getColorForDatabase(database.id))
        }

        binding.legendCard.visibility = View.VISIBLE
        legendAdapter.updateLegend(legendItems)
        Log.d(TAG, "Updated legend with ${legendItems.size} items")
    }

    private fun showNetworkInfo(point: NetworkPoint) {
        val dialog = BottomSheetDialog(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_network_details, null)

        val macAddress = viewModel.convertBssidToString(point.bssidDecimal)
        val database = viewModel.availableDatabases.value?.find { it.id == point.databaseId }
        val databaseName = database?.let { formatSourcePath(it.path) } ?: getString(R.string.unknown_database)

        dialogView.apply {
            findViewById<View>(R.id.viewDatabaseColor).setBackgroundColor(getColorForDatabase(point.databaseId))
            findViewById<TextView>(R.id.textViewBssid).text = macAddress
            findViewById<TextView>(R.id.textViewDatabaseName).text = databaseName
            findViewById<TextView>(R.id.textViewRecordCount).text = getString(R.string.multiple_records, point.allRecords.size)

            val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewRecords)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            val adapter = NetworkRecordsAdapter(point.allRecords, requireContext(), macAddress)
            recyclerView.adapter = adapter

            findViewById<ImageButton>(R.id.buttonCopyCoordinates).setOnClickListener {
                val coordinates = "${point.latitude}, ${point.longitude}"
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(getString(R.string.coordinates), coordinates)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), getString(R.string.coordinates_copied), Toast.LENGTH_SHORT).show()
            }

            findViewById<ImageButton>(R.id.buttonOpenMap).setOnClickListener {
                val geoUri = "geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}(${point.essid ?: macAddress})"
                val mapIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(geoUri))
                mapIntent.setPackage("com.google.android.apps.maps")

                try {
                    startActivity(mapIntent)
                } catch (e: Exception) {
                    val genericMapIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(geoUri))
                    if (genericMapIntent.resolveActivity(requireContext().packageManager) != null) {
                        startActivity(genericMapIntent)
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.no_map_app_found), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            findViewById<ImageButton>(R.id.buttonCreateQr).setOnClickListener {
                val firstValidRecord = point.allRecords.firstOrNull { !it.password.isNullOrBlank() }
                if (firstValidRecord != null) {
                    adapter.showQrForRecord(firstValidRecord)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.password_not_available), Toast.LENGTH_SHORT).show()
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
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_network_data)))
            }
        }

        dialog.setContentView(dialogView)
        dialog.show()
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

        markers.forEach { marker ->
            binding.map.overlays.remove(marker)
        }
        markers.clear()

        canvasOverlay.updatePoints(emptyList())
        binding.map.postInvalidate()

        if (selectedDatabases.isEmpty()) {
            binding.legendCard.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Checking databases")
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        userLocationManager.startLocationUpdates()

        checkDatabaseValidity()
        updateLegend()

        if (DbSetupViewModel.needDataRefresh) {
            Log.d(TAG, "Data refresh needed, reloading available databases")
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
            Log.d(TAG, "Removing invalid databases from selection: ${invalidDatabases.map { it.id }}")
            selectedDatabases.removeAll(invalidDatabases)
            databaseAdapter.notifyDataSetChanged()

            clearMarkers()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        userLocationManager.stopLocationUpdates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        userLocationManager.onDestroy()
        _binding = null
    }
}