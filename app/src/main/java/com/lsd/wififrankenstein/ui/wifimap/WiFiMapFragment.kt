package com.lsd.wififrankenstein.ui.wifimap

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentWifiMapBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
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

    private val databaseColors = mutableMapOf<String, Int>()
    private val availableColors = listOf(
        Color.RED,
        Color.BLUE,
        Color.GREEN,
        Color.YELLOW,
        Color.MAGENTA,
        Color.CYAN,
        Color.rgb(255, 165, 0),
        Color.rgb(128, 0, 128),
        Color.rgb(165, 42, 42),
        Color.rgb(255, 192, 203)
    )
    private var nextColorIndex = 0

    private var lastMapUpdateTime = 0L
    private val MAP_UPDATE_DEBOUNCE_MS = 300L

    companion object {
        private const val DEFAULT_ZOOM = 5.0
        private const val DEFAULT_LAT = 55.7558
        private const val DEFAULT_LON = 37.6173
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
            scheduleMapUpdate()
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

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent): Boolean {
                    scheduleMapUpdate()
                    return true
                }

                override fun onZoom(event: ZoomEvent): Boolean {
                    scheduleMapUpdate()
                    return true
                }
            })
        }
    }

    private fun scheduleMapUpdate() {
        val currentTime = System.currentTimeMillis()
        lastMapUpdateTime = currentTime

        lifecycleScope.launch {
            delay(MAP_UPDATE_DEBOUNCE_MS)
            if (lastMapUpdateTime == currentTime) {
                updateVisiblePoints()
            }
        }
    }

    private fun setupRecyclerView() {
        binding.databasesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            databaseAdapter = MapDatabaseAdapter(
                emptyList(),
                selectedDatabases,
                {
                    viewModel.clearCache()
                    clearMarkers()
                    scheduleMapUpdate()
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
                    viewModel.clearCache()
                    clearMarkers()
                    scheduleMapUpdate()
                },
                viewModel
            )
            binding.databasesRecyclerView.adapter = databaseAdapter
        }
    }

    private fun setupLocationButton() {
        binding.fabLocation.setOnClickListener {
            userLocationMarker?.let { marker ->
                binding.map.controller.animateTo(marker.position, 18.0, 400L)
            } ?: run {
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

        userLocationManager.locationError.observe(viewLifecycleOwner) { error ->
            Snackbar.make(binding.root, error, Snackbar.LENGTH_SHORT).show()
        }
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
                scheduleMapUpdate()
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

    private fun observeViewModel() {
        viewModel.loadingProgress.observe(viewLifecycleOwner) { count ->
            Log.d(TAG, "Loading progress updated: $count")
            when {
                count < 100 -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.isIndeterminate = true
                    binding.textViewProgress.visibility = View.VISIBLE
                    binding.textViewProgress.text = when {
                        count < 50 -> getString(R.string.loading_points_progress)
                        count < 75 -> getString(R.string.clustering_points)
                        else -> getString(R.string.rendering_markers)
                    }
                }
                else -> {
                    binding.progressBar.visibility = View.GONE
                    binding.textViewProgress.visibility = View.GONE
                }
            }
        }

        viewModel.points.observe(viewLifecycleOwner) { points ->
            Log.d(TAG, "Received ${points.size} points from ViewModel")
            updateMarkers(points)
            binding.loadingIndicator.visibility = View.GONE
            binding.textViewProgress.visibility = View.GONE
        }

        viewModel.addReadOnlyDb.observe(viewLifecycleOwner) { dbItem ->
            dbItem?.let {
                selectedDatabases.add(it)
                databaseAdapter.notifyDataSetChanged()
                scheduleMapUpdate()
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
        val zoom = binding.map.zoomLevelDouble
        val minZoom = viewModel.getMinZoomForMarkers()
        val boundingBox = binding.map.boundingBox

        Log.d(TAG, "=== MAP UPDATE DEBUG ===")
        Log.d(TAG, "Current zoom: $zoom")
        Log.d(TAG, "Min zoom for markers: $minZoom")
        Log.d(TAG, "Bounding box: $boundingBox")
        Log.d(TAG, "Selected databases: ${selectedDatabases.size}")

        val zoomCategory = when {
            zoom >= 12 -> "CITY+ (≥12) - NO LIMITS"
            zoom >= 10 -> "REGIONAL (10-11) - 20k points"
            zoom >= 8 -> "AREA (8-9) - 15k points"
            else -> "COUNTRY (<8) - 10k points"
        }
        Log.d(TAG, "Zoom category: $zoomCategory")

        if (zoom < minZoom) {
            Log.d(TAG, "Zoom too low, clearing markers")
            clearMarkers()
            binding.progressBar.visibility = View.GONE
            binding.textViewProgress.text = getString(R.string.zoom_in_message)
            binding.textViewProgress.visibility = View.VISIBLE
            return
        }

        if (selectedDatabases.isEmpty()) {
            Log.d(TAG, "No databases selected")
            clearMarkers()
            binding.progressBar.visibility = View.GONE
            binding.textViewProgress.text = getString(R.string.select_database_message)
            binding.textViewProgress.visibility = View.VISIBLE
            return
        }

        binding.textViewProgress.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE

        if (boundingBox == null) {
            Log.d(TAG, "Bounding box is null")
            return
        }

        binding.textViewProgress.visibility = View.GONE

        updateJob?.cancel()

        updateJob = lifecycleScope.launch {
            val currentColors = selectedDatabases.associate { database ->
                database.id to getColorForDatabase(database.id)
            }

            viewModel.loadPointsInBoundingBox(
                boundingBox,
                zoom,
                selectedDatabases,
                currentColors
            )
        }
    }

    private fun updateMarkers(visiblePoints: List<NetworkPoint>) {
        Log.d(TAG, "Updating canvas overlay with ${visiblePoints.size} visible points")

        lifecycleScope.launch(Dispatchers.Main) {
            canvasOverlay.updatePoints(visiblePoints)
            binding.map.postInvalidate()
            updateLegend()

            binding.progressBar.visibility = View.GONE
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
        return databaseColors.getOrPut(databaseId) {
            val color = availableColors[nextColorIndex % availableColors.size]
            nextColorIndex++
            color
        }
    }

    private fun updateLegend() {
        val legendItems = selectedDatabases.map { database ->
            Pair(database, getColorForDatabase(database.id))
        }

        if (legendItems.isEmpty()) {
            binding.legendCard.visibility = View.GONE
        } else {
            binding.legendCard.visibility = View.VISIBLE
            legendAdapter.updateLegend(legendItems)
        }
    }

    private fun showNetworkInfo(point: NetworkPoint) {
        val dialog = BottomSheetDialog(requireContext())
        val dialogView = layoutInflater.inflate(R.layout.dialog_network_info, null)

        val macAddress = viewModel.convertBssidToString(point.bssidDecimal)

        dialogView.apply {
            findViewById<View>(R.id.viewDatabaseColor).setBackgroundColor(getColorForDatabase(point.databaseId))
            findViewById<android.widget.TextView>(R.id.textViewEssid).text = point.essid ?: getString(R.string.unknown_ssid)
            findViewById<android.widget.TextView>(R.id.textViewBssid).text = macAddress
            findViewById<android.widget.TextView>(R.id.textViewPassword).text =
                point.password?.let { getString(R.string.password_format, it) } ?: getString(R.string.not_available)
            findViewById<android.widget.TextView>(R.id.textViewWpsPin).text =
                point.wpsPin?.let { getString(R.string.wps_pin_format, it) } ?: getString(R.string.not_available)
            findViewById<android.widget.TextView>(R.id.textViewSource).text = getString(R.string.source_format, point.source)
        }

        dialog.setContentView(dialogView)
        dialog.show()
    }

    private fun clearMarkers() {
        if (_binding == null) return

        canvasOverlay.updatePoints(emptyList())
        binding.map.postInvalidate()
        binding.legendCard.visibility = View.GONE
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
        _binding = null
    }
}