package com.lsd.wififrankenstein.ui.ipranges

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentIpRangesBinding
import com.lsd.wififrankenstein.util.Log
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

class IpRangesFragment : Fragment() {

    private var _binding: FragmentIpRangesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: IpRangesViewModel by viewModels()
    private lateinit var adapter: IpRangesAdapter

    private lateinit var mapView: MapView
    private var locationMarker: Marker? = null
    private var radiusOverlay: Polygon? = null
    private var isUpdatingFromMap = false
    private var isUpdatingFromFields = false

    companion object {
        private const val TAG = "IpRangesFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIpRangesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMap()
        setupRecyclerView()
        setupViews()
        observeViewModel()

        viewModel.loadSources()
    }

    private fun setupMap() {
        Configuration.getInstance().load(requireContext(),
            requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

        mapView = binding.mapView
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
        mapView.setTilesScaledToDpi(true)

        val controller = mapView.controller
        controller.setZoom(6.0)
        controller.setCenter(GeoPoint(55.7558, 37.6176))

        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (!isUpdatingFromFields) {
                    updateLocationFromMap(p)
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                return false
            }
        }

        val mapEventsOverlay = MapEventsOverlay(mapEventsReceiver)
        mapView.overlays.add(0, mapEventsOverlay)
    }

    private fun setupRecyclerView() {
        adapter = IpRangesAdapter(
            onCopyClick = { range ->
                copyToClipboard(range.range)
            },
            onSelectionChanged = { selectedItems ->
                updateSelectionUI(selectedItems.size)
            }
        )
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.resultsRecyclerView.adapter = adapter
    }

    private fun setupViews() {
        binding.searchButton.setOnClickListener {
            val latitude = binding.latitudeInput.text.toString().trim().replace(",", ".")
            val longitude = binding.longitudeInput.text.toString().trim().replace(",", ".")
            val radius = binding.radiusInput.text.toString().trim().replace(",", ".")

            if (validateInput(latitude, longitude, radius)) {
                val selectedSources = getSelectedSources()
                if (selectedSources.isNotEmpty()) {
                    val lat = parseCoordinate(latitude)
                    val lon = parseCoordinate(longitude)
                    var rad = parseCoordinate(radius)

                    if (lat != null && lon != null && rad != null) {
                        val hasApiSources = hasApiSourcesSelected()
                        if (hasApiSources && rad > 25.0) {
                            rad = 25.0
                            binding.radiusInput.setText(String.format(Locale.US, "%.1f", rad))
                            showError(getString(R.string.radius_limited_for_api))
                        }

                        viewModel.searchIpRanges(lat, lon, rad, selectedSources)
                    }
                } else {
                    showError(getString(R.string.select_at_least_one_source))
                }
            }
        }

        binding.selectAllButton.setOnClickListener {
            adapter.selectAll()
        }

        binding.copySelectedButton.setOnClickListener {
            val selectedItems = adapter.getSelectedItems()
            if (selectedItems.isNotEmpty()) {
                copySelectedToClipboard(selectedItems)
            } else {
                showError(getString(R.string.no_ranges_selected))
            }
        }

        setupTextWatchers()
    }

    private fun setupTextWatchers() {
        val coordinateTextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingFromMap) {
                    updateLocationFromFields()
                }
            }
        }

        binding.latitudeInput.addTextChangedListener(coordinateTextWatcher)
        binding.longitudeInput.addTextChangedListener(coordinateTextWatcher)

        binding.radiusInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateRadiusOverlay()
                validateCurrentInput()
            }
        })
    }

    private fun updateLocationFromMap(geoPoint: GeoPoint) {
        isUpdatingFromMap = true

        binding.latitudeInput.setText(String.format(Locale.US, "%.6f", geoPoint.latitude))
        binding.longitudeInput.setText(String.format(Locale.US, "%.6f", geoPoint.longitude))

        updateLocationMarker(geoPoint)
        updateRadiusOverlay()

        isUpdatingFromMap = false

        showError(getString(R.string.location_selected))
    }

    private fun updateLocationFromFields() {
        isUpdatingFromFields = true

        val latText = binding.latitudeInput.text.toString().trim().replace(",", ".")
        val lonText = binding.longitudeInput.text.toString().trim().replace(",", ".")

        val lat = parseCoordinate(latText)
        val lon = parseCoordinate(lonText)

        if (lat != null && lon != null && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
            val geoPoint = GeoPoint(lat, lon)
            updateLocationMarker(geoPoint)
            mapView.controller.setCenter(geoPoint)
            updateRadiusOverlay()
        }

        isUpdatingFromFields = false
    }

    private fun updateLocationMarker(geoPoint: GeoPoint) {
        locationMarker?.let { mapView.overlays.remove(it) }

        locationMarker = Marker(mapView).apply {
            position = geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Selected Location"
            snippet = String.format(Locale.US, "%.6f, %.6f", geoPoint.latitude, geoPoint.longitude)
            infoWindow = null
        }

        mapView.overlays.add(locationMarker)
        mapView.invalidate()
    }

    private fun updateRadiusOverlay() {
        radiusOverlay?.let { mapView.overlays.remove(it) }

        val latText = binding.latitudeInput.text.toString().trim().replace(",", ".")
        val lonText = binding.longitudeInput.text.toString().trim().replace(",", ".")
        val radiusText = binding.radiusInput.text.toString().trim().replace(",", ".")

        val lat = parseCoordinate(latText)
        val lon = parseCoordinate(lonText)
        val radius = parseCoordinate(radiusText)

        val hasApiSources = hasApiSourcesSelected()
        val maxRadius = if (hasApiSources) 25.0 else 40.0

        if (lat != null && lon != null && radius != null && radius > 0 && radius <= maxRadius) {
            val center = GeoPoint(lat, lon)
            val circlePoints = createCirclePoints(center, radius)

            val fillColor = if (hasApiSources && radius > 25.0) {
                Color.argb(50, 200, 100, 0)
            } else {
                Color.argb(50, 0, 100, 200)
            }
            val strokeColor = if (hasApiSources && radius > 25.0) {
                Color.argb(150, 200, 100, 0)
            } else {
                Color.argb(150, 0, 100, 200)
            }

            radiusOverlay = Polygon().apply {
                points = circlePoints
                this.fillColor = fillColor
                this.strokeColor = strokeColor
                strokeWidth = 2.0f
                infoWindow = null
            }

            mapView.overlays.add(radiusOverlay)
            mapView.invalidate()
        }
    }

    private fun createCirclePoints(center: GeoPoint, radiusKm: Double): ArrayList<GeoPoint> {
        val points = ArrayList<GeoPoint>()
        val earthRadius = 6371.0
        val radiusInDegrees = radiusKm / earthRadius * (180.0 / PI)

        for (i in 0..36) {
            val angle = i * 10.0 * PI / 180.0
            val lat = center.latitude + radiusInDegrees * cos(angle)
            val lon = center.longitude + radiusInDegrees * kotlin.math.sin(angle) / cos(center.latitude * PI / 180.0)
            points.add(GeoPoint(lat, lon))
        }

        return points
    }

    private fun observeViewModel() {
        viewModel.sources.observe(viewLifecycleOwner) { sources ->
            setupSourceCheckboxes(sources)
            updateRadiusHint()
        }

        viewModel.ipRanges.observe(viewLifecycleOwner) { ranges ->
            adapter.submitList(ranges)

            binding.statusText.visibility = View.VISIBLE
            binding.statusText.text = if (ranges.isEmpty()) {
                getString(R.string.no_ip_ranges_found)
            } else {
                getString(R.string.ip_ranges_found, ranges.size)
            }

            binding.actionButtonsContainer.visibility = if (ranges.isNotEmpty()) View.VISIBLE else View.GONE
            updateSelectionUI(0)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                binding.loadingBar.startAnimation()
            } else {
                binding.loadingBar.stopAnimation()
            }

            binding.searchButton.isEnabled = !isLoading

            if (isLoading) {
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = getString(R.string.searching_ip_ranges)
                binding.actionButtonsContainer.visibility = View.GONE
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                showError(it)
                viewModel.clearError()
            }
        }
    }

    private fun setupSourceCheckboxes(sources: List<IpRangeSource>) {
        binding.sourcesContainer.removeAllViews()

        sources.forEach { source ->
            val checkBox = MaterialCheckBox(requireContext()).apply {
                text = source.name
                isChecked = source.isSelected
                setOnCheckedChangeListener { _, isChecked ->
                    viewModel.updateSourceSelection(source.id, isChecked)
                    updateRadiusHint()
                    validateCurrentInput()
                }
            }
            binding.sourcesContainer.addView(checkBox)
        }
        updateRadiusHint()
    }

    private fun hasApiSourcesSelected(): Boolean {
        val selectedSources = getSelectedSources()
        val apiSources = viewModel.sources.value?.filter {
            selectedSources.contains(it.id) && it.type == IpRangeSourceType.API
        } ?: emptyList()
        return apiSources.isNotEmpty()
    }

    private fun updateRadiusHint() {
        val hasApiSources = hasApiSourcesSelected()
        val hint = if (hasApiSources) {
            "${getString(R.string.radius_km)} (${getString(R.string.max_radius_25km_api)})"
        } else {
            "${getString(R.string.radius_km)} (${getString(R.string.max_radius_40km)})"
        }
        binding.radiusInputLayout.hint = hint
    }

    private fun validateCurrentInput() {
        val latitude = binding.latitudeInput.text.toString().trim()
        val longitude = binding.longitudeInput.text.toString().trim()
        val radius = binding.radiusInput.text.toString().trim()

        if (latitude.isNotEmpty() && longitude.isNotEmpty() && radius.isNotEmpty()) {
            validateInput(latitude, longitude, radius)
        }
    }

    private fun updateSelectionUI(selectedCount: Int) {
        if (selectedCount > 0) {
            binding.selectionCountText.visibility = View.VISIBLE
            binding.selectionCountText.text = getString(R.string.selected_count, selectedCount)
            binding.selectAllButton.text = getString(R.string.clear_selection)
            binding.selectAllButton.setOnClickListener {
                adapter.clearSelection()
            }
        } else {
            binding.selectionCountText.visibility = View.GONE
            binding.selectAllButton.text = getString(R.string.select_all)
            binding.selectAllButton.setOnClickListener {
                adapter.selectAll()
            }
        }
    }

    private fun validateInput(latitude: String, longitude: String, radius: String): Boolean {
        var isValid = true

        val lat = parseCoordinate(latitude.replace(",", "."))
        if (lat == null || lat < -90 || lat > 90) {
            binding.latitudeInputLayout.error = getString(R.string.invalid_coordinates)
            isValid = false
        } else {
            binding.latitudeInputLayout.error = null
        }

        val lon = parseCoordinate(longitude.replace(",", "."))
        if (lon == null || lon < -180 || lon > 180) {
            binding.longitudeInputLayout.error = getString(R.string.invalid_coordinates)
            isValid = false
        } else {
            binding.longitudeInputLayout.error = null
        }

        val rad = parseCoordinate(radius.replace(",", "."))
        val hasApiSources = hasApiSourcesSelected()
        val maxRadius = if (hasApiSources) 25.0 else 40.0

        if (rad == null || rad < 0.1 || rad > maxRadius) {
            binding.radiusInputLayout.error = if (hasApiSources) {
                getString(R.string.invalid_radius_api)
            } else {
                getString(R.string.invalid_radius_local)
            }
            isValid = false
        } else {
            binding.radiusInputLayout.error = null
        }

        return isValid
    }

    private fun getSelectedSources(): List<String> {
        val selectedSources = mutableListOf<String>()
        for (i in 0 until binding.sourcesContainer.childCount) {
            val checkBox = binding.sourcesContainer.getChildAt(i) as MaterialCheckBox
            if (checkBox.isChecked) {
                selectedSources.add(viewModel.sources.value?.get(i)?.id ?: "")
            }
        }
        return selectedSources
    }

    private fun parseCoordinate(coordinate: String): Double? {
        return try {
            val normalizedCoordinate = coordinate.replace(",", ".")
            NumberFormat.getInstance(Locale.US).parse(normalizedCoordinate)?.toDouble()
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse coordinate: $coordinate", e)
            null
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(getString(R.string.range), text)
        clipboard.setPrimaryClip(clip)
        showError(getString(R.string.copied_to_clipboard))
    }

    private fun copySelectedToClipboard(ranges: List<IpRangeResult>) {
        val text = ranges.joinToString("\n") { "${it.range} - ${it.description}" }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("IP Ranges", text)
        clipboard.setPrimaryClip(clip)
        showError(getString(R.string.ranges_copied, ranges.size))
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}