package com.lsd.wififrankenstein.ui.maclocation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.InputFilter
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentMacLocationBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale

class MacLocationFragment : Fragment() {
    companion object {
        private val MAC_INPUT_REGEX = Regex("[0-9A-Fa-f:-]*")
        private const val KEY_SETTINGS_EXPANDED = "settings_expanded"
        private const val KEY_CONSOLE_EXPANDED = "console_expanded"
    }

    private var isSettingsExpanded = false
    private var isConsoleExpanded = false

    private var _binding: FragmentMacLocationBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MacLocationViewModel
    private lateinit var map: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = getString(R.string.app_name)
            load(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()))
        }
        isSettingsExpanded = savedInstanceState?.getBoolean(KEY_SETTINGS_EXPANDED) ?: false
        isConsoleExpanded = savedInstanceState?.getBoolean(KEY_CONSOLE_EXPANDED) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMacLocationBinding.inflate(inflater, container, false)
        viewModel = ViewModelProvider(this)[MacLocationViewModel::class.java]

        map = binding.map.apply {
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)
            setTileSource(TileSourceFactory.MAPNIK)
            controller.apply {
                setZoom(2.0)
                setCenter(GeoPoint(0.0, 0.0))
            }
        }

        setupObservers()
        setupListeners()
        setupSettings()
        applyPanelStates()

        viewModel.savedApiKeys.observe(viewLifecycleOwner) { apiKeys ->
            binding.wigleApiInput.setText(apiKeys.wigleApi)
            binding.googleApiInput.setText(apiKeys.googleApi)
            binding.combainApiInput.setText(apiKeys.combainApi)
            binding.yandexLocatorApiInput.setText(apiKeys.yandexLocatorApi)
            binding.mylnikovApiInput.setText(apiKeys.mylnikovApi)
        }

        return binding.root
    }

    private fun applyPanelStates() {
        binding.settingsContent.visibility = if (isSettingsExpanded) View.VISIBLE else View.GONE
        binding.expandIcon.rotation = if (isSettingsExpanded) 180f else 0f
        binding.logTextView.visibility = if (isConsoleExpanded) View.VISIBLE else View.GONE
        binding.consoleExpandIcon.rotation = if (isConsoleExpanded) 180f else 0f
    }

    private fun setupSettings() {
        binding.settingsHeader.setOnClickListener {
            isSettingsExpanded = !isSettingsExpanded
            binding.settingsContent.visibility = if (isSettingsExpanded) View.VISIBLE else View.GONE
            binding.expandIcon.rotation = if (isSettingsExpanded) 180f else 0f
            TransitionManager.beginDelayedTransition(binding.apiSettingsCard)
        }

        binding.saveApiButton.setOnClickListener {
            val wigleApi = binding.wigleApiInput.text.toString()
            val googleApi = binding.googleApiInput.text.toString()
            val combainApi = binding.combainApiInput.text.toString()
            val yandexLocatorApi = binding.yandexLocatorApiInput.text.toString()
            val mylnikovApi = binding.mylnikovApiInput.text.toString()

            viewModel.saveApiKeys(wigleApi, googleApi, combainApi, yandexLocatorApi, mylnikovApi)
            Snackbar.make(binding.root, R.string.api_keys_saved, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupObservers() {
        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            updateMap(results)
        }

        viewModel.logMessages.observe(viewLifecycleOwner) { logMessages ->
            binding.logTextView.text = logMessages
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                binding.progressIndicator.startAnimation()
                binding.searchButton.isEnabled = false
            } else {
                binding.progressIndicator.stopAnimation()
                binding.searchButton.isEnabled = true
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) showError(error)
        }
    }

    private fun addResultCard(result: MacLocationViewModel.LocationResult) {
        if (_binding == null || result.latitude == null || result.longitude == null) return

        val cardView = LayoutInflater.from(requireContext()).inflate(
            R.layout.item_location_result_card,
            binding.resultsContainer,
            false
        ) as MaterialCardView

        val moduleText = cardView.findViewById<TextView>(R.id.moduleText)
        val macAddressText = cardView.findViewById<TextView>(R.id.macAddressText)
        val ssidText = cardView.findViewById<TextView>(R.id.ssidText)
        val coordinatesText = cardView.findViewById<TextView>(R.id.coordinatesText)
        val coordinatesWarningText = cardView.findViewById<TextView>(R.id.coordinatesWarningText)
        val openMapsButton = cardView.findViewById<MaterialButton>(R.id.openMapsButton)

        moduleText.text = getString(R.string.source_format, result.module.uppercase(Locale.ROOT))

        if (result.bssid != null) {
            macAddressText.text = result.bssid
            macAddressText.visibility = View.VISIBLE
        } else {
            macAddressText.visibility = View.GONE
        }

        if (result.ssid != null && result.ssid.isNotEmpty()) {
            ssidText.text = result.ssid
            ssidText.visibility = View.VISIBLE
        } else {
            ssidText.visibility = View.GONE
        }

        coordinatesText.text = getString(R.string.coordinates) + ": " +
                String.format(Locale.US, "%.6f, %.6f", result.latitude, result.longitude)

        val latStr = String.format(Locale.US, "%.1f", result.latitude)
        val lonStr = String.format(Locale.US, "%.1f", result.longitude)
        if ((latStr == "-180.0" && lonStr == "0.0") ||
            (latStr == "-180.0" && lonStr == "-180.0") ||
            (result.latitude == -180.0 && result.longitude == 0.0) ||
            (result.latitude == -180.0 && result.longitude == -180.0)
        ) {
            coordinatesWarningText.text = getString(R.string.coordinates_may_be_incorrect)
            coordinatesWarningText.visibility = View.VISIBLE
        } else {
            coordinatesWarningText.visibility = View.GONE
        }

        openMapsButton.setOnClickListener {
            val label =
                result.bssid ?: result.ssid ?: getString(R.string.mac_location_default_label)
            val uri = Uri.parse(
                "geo:${result.latitude},${result.longitude}?q=${result.latitude},${result.longitude}(${
                    Uri.encode(label)
                })"
            )
            val intent = Intent(Intent.ACTION_VIEW, uri)

            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(Intent.createChooser(intent, getString(R.string.open_map)))
            } else {
                val browserUri =
                    Uri.parse("https://maps.google.com/maps?q=${result.latitude},${result.longitude}")
                val browserIntent = Intent(Intent.ACTION_VIEW, browserUri)
                if (browserIntent.resolveActivity(requireContext().packageManager) != null) {
                    startActivity(browserIntent)
                } else {
                    Snackbar.make(binding.root, R.string.no_map_app, Snackbar.LENGTH_LONG).show()
                }
            }
        }

        binding.resultsContainer.addView(cardView)
    }

    private fun setupListeners() {
        binding.searchTypeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.search_mac -> {
                        binding.inputLayout.hint = getString(R.string.mac_address_hint)
                        binding.inputQuery.text?.clear()
                        binding.inputQuery.filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
                            if (source.toString().matches(MAC_INPUT_REGEX)) source else ""
                        })
                    }

                    R.id.search_ssid -> {
                        binding.inputLayout.hint = getString(R.string.ssid_hint)
                        binding.inputQuery.text?.clear()
                        binding.inputQuery.filters = arrayOfNulls(0)
                    }
                }
            }
        }

        binding.searchButton.setOnClickListener {
            val query = binding.inputQuery.text.toString().trim()
            val searchType = if (binding.searchMac.isChecked) "MAC" else "SSID"

            if (searchType == "MAC" && !viewModel.isValidMacAddress(query)) {
                Snackbar.make(binding.root, R.string.error_invalid_mac, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (searchType == "SSID" && query.isBlank()) {
                Snackbar.make(binding.root, R.string.error_empty_ssid, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.inputQuery.windowToken, 0)

            viewModel.saveApiKeys(
                binding.wigleApiInput.text.toString(),
                binding.googleApiInput.text.toString(),
                binding.combainApiInput.text.toString(),
                binding.yandexLocatorApiInput.text.toString(),
                binding.mylnikovApiInput.text.toString()
            )

            viewModel.search(MacLocationViewModel.SearchType(searchType, query))
        }

        binding.consoleHeader.setOnClickListener {
            isConsoleExpanded = !isConsoleExpanded
            binding.logTextView.visibility = if (isConsoleExpanded) View.VISIBLE else View.GONE
            binding.consoleExpandIcon.rotation = if (isConsoleExpanded) 180f else 0f
        }
    }

    private fun updateMap(results: List<MacLocationViewModel.LocationResult>) {
        if (_binding == null) return

        val containerChildCount = binding.resultsContainer.childCount
        if (containerChildCount == 0 || results.size < containerChildCount) {
            binding.resultsContainer.removeAllViews()
            results.forEach { result ->
                addResultCard(result)
            }
        } else {
            for (i in containerChildCount until results.size) {
                addResultCard(results[i])
            }
        }

        map.overlays.clear()

        val points = mutableListOf<GeoPoint>()

        results.forEach { result ->
            result.latitude?.let { lat ->
                result.longitude?.let { lon ->
                    val latStr = String.format(Locale.US, "%.1f", lat)
                    val lonStr = String.format(Locale.US, "%.1f", lon)
                    val isInvalidCoordinates = (latStr == "-180.0" && lonStr == "0.0") ||
                            (latStr == "-180.0" && lonStr == "-180.0") ||
                            (lat == -180.0 && lon == 0.0) ||
                            (lat == -180.0 && lon == -180.0)

                    if (!isInvalidCoordinates) {
                        val point = GeoPoint(lat, lon)
                        points.add(point)

                        val marker = Marker(map).apply {
                            position = point
                            title = result.module
                            snippet = getString(
                                R.string.mac_marker_format,
                                result.bssid ?: getString(R.string.not_available),
                                result.ssid ?: getString(R.string.not_available)
                            )
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        map.overlays.add(marker)
                    }
                }
            }
        }

        if (points.isNotEmpty()) {
            if (points.size == 1) {
                map.controller.apply {
                    setZoom(15.0)
                    setCenter(points[0])
                }
            } else {
                val boundingBox = BoundingBox.fromGeoPoints(points)
                val expandedBox = BoundingBox(
                    boundingBox.latNorth + 0.01,
                    boundingBox.lonEast + 0.01,
                    boundingBox.latSouth - 0.01,
                    boundingBox.lonWest - 0.01
                )
                map.zoomToBoundingBox(expandedBox, true, 100)

                if (map.zoomLevelDouble > 18.0) {
                    map.controller.setZoom(15.0)
                }
            }
        }

        map.invalidate()
    }

    private fun showError(message: String) {
        if (_binding == null) return
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SETTINGS_EXPANDED, isSettingsExpanded)
        outState.putBoolean(KEY_CONSOLE_EXPANDED, isConsoleExpanded)
    }

    override fun onResume() {
        super.onResume()
        if (::map.isInitialized) map.onResume()
    }

    override fun onPause() {
        if (::map.isInitialized) map.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        if (::map.isInitialized) map.onDetach()
        super.onDestroyView()
        _binding = null
    }
}