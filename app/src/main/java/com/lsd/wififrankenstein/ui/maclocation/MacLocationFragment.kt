package com.lsd.wififrankenstein.ui.maclocation

import android.content.Context
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.InputFilter
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentMacLocationBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MacLocationFragment : Fragment() {

    private var isSettingsExpanded = false

    private var _binding: FragmentMacLocationBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: MacLocationViewModel
    private lateinit var map: MapView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMacLocationBinding.inflate(inflater, container, false)
        viewModel = ViewModelProvider(this)[MacLocationViewModel::class.java]

        setupMap()
        setupObservers()
        setupListeners()
        setupSettings()

        viewModel.savedApiKeys.observe(viewLifecycleOwner) { apiKeys ->
            binding.wigleApiInput.setText(apiKeys.wigleApi)
            binding.googleApiInput.setText(apiKeys.googleApi)
            binding.combainApiInput.setText(apiKeys.combainApi)
        }

        return binding.root
    }

    private fun setupSettings() {
        binding.settingsHeader.setOnClickListener {
            isSettingsExpanded = !isSettingsExpanded
            binding.settingsContent.visibility = if (isSettingsExpanded) View.VISIBLE else View.GONE
            binding.expandIcon.rotation = if (isSettingsExpanded) 180f else 0f
        }

        binding.saveApiButton.setOnClickListener {
            val wigleApi = binding.wigleApiInput.text.toString()
            val googleApi = binding.googleApiInput.text.toString()
            val combainApi = binding.combainApiInput.text.toString()

            viewModel.saveApiKeys(wigleApi, googleApi, combainApi)
            Snackbar.make(binding.root, R.string.api_keys_saved, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupMap() {
        Configuration.getInstance().apply {
            userAgentValue = getString(R.string.app_name)
            load(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()))
        }

        map = binding.map.apply {
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)
        }

        map.controller.apply {
            setZoom(3.0)
            setCenter(GeoPoint(0.0, 0.0))
        }

        map.setTileSource(TileSourceFactory.MAPNIK)
    }

    private fun setupObservers() {
        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            updateMap(results)
        }

        viewModel.logMessages.observe(viewLifecycleOwner) { logMessages ->
            binding.logTextView.text = logMessages
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            showError(error)
        }
    }

    private fun setupListeners() {

        binding.searchTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.search_mac -> {
                    binding.inputLayout.hint = getString(R.string.mac_address_hint)
                    binding.inputQuery.text?.clear()
                    binding.inputQuery.filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
                        val pattern = "[0-9A-Fa-f:-]*".toRegex()
                        if (source.toString().matches(pattern)) source else ""
                    })
                }
                R.id.search_ssid -> {
                    binding.inputLayout.hint = getString(R.string.ssid_hint)
                    binding.inputQuery.text?.clear()
                    binding.inputQuery.filters = arrayOfNulls(0)
                }
            }
        }

        binding.searchButton.setOnClickListener {
            val query = binding.inputQuery.text.toString()
            val searchType = if (binding.searchMac.isChecked) "MAC" else "SSID"

            if (searchType == "MAC" && !viewModel.isValidMacAddress(query)) {
                Snackbar.make(binding.root, R.string.error_invalid_mac, Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.inputQuery.windowToken, 0)

            viewModel.saveApiKeys(
                binding.wigleApiInput.text.toString(),
                binding.googleApiInput.text.toString(),
                binding.combainApiInput.text.toString()
            )

            viewModel.search(MacLocationViewModel.SearchType(searchType, query))
        }

        binding.map.setMultiTouchControls(true)
        binding.map.controller.setZoom(3.0)

        binding.settingsHeader.setOnClickListener {
            val isVisible = binding.settingsContent.isVisible
            binding.settingsContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.expandIcon.rotation = if (isVisible) 0f else 180f
            TransitionManager.beginDelayedTransition(binding.apiSettingsCard)
        }

        binding.saveApiButton.setOnClickListener {
            viewModel.saveApiKeys(
                binding.wigleApiInput.text.toString(),
                binding.googleApiInput.text.toString(),
                binding.combainApiInput.text.toString()
            )
            Snackbar.make(binding.root, R.string.api_keys_saved, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun updateMap(results: List<MacLocationViewModel.LocationResult>) {
        map.overlays.clear()

        val points = mutableListOf<GeoPoint>()

        results.forEach { result ->
            result.latitude?.let { lat ->
                result.longitude?.let { lon ->
                    val point = GeoPoint(lat, lon)
                    points.add(point)

                    val marker = Marker(map).apply {
                        position = point
                        title = result.module
                        snippet = buildString {
                            append("BSSID: ${result.bssid ?: "N/A"}\n")
                            append("SSID: ${result.ssid ?: "N/A"}")
                        }
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    map.overlays.add(marker)
                }
            }
        }

        if (points.isNotEmpty()) {
            val boundingBox = BoundingBox.fromGeoPoints(points)
            map.zoomToBoundingBox(boundingBox, true, 50)
        }

        map.invalidate()
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}