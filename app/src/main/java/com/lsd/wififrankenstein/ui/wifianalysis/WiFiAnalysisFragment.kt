package com.lsd.wififrankenstein.ui.wifianalysis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentWifiAnalysisBinding
import com.lsd.wififrankenstein.ui.wifiscanner.WiFiScannerViewModel

class WiFiAnalysisFragment : Fragment() {

    private var _binding: FragmentWifiAnalysisBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WiFiAnalysisViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WiFiAnalysisViewModel(requireActivity().application) as T
            }
        }
    }

    private val wifiScannerViewModel: WiFiScannerViewModel by activityViewModels()

    private lateinit var channelAnalysisAdapter: ChannelAnalysisAdapter
    private lateinit var recommendationAdapter: ChannelRecommendationAdapter

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startWifiScan()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.location_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWifiAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupBandToggle()
        setupClickListeners()
        observeViewModel()
        observeWifiScannerViewModel()

        checkInitialData()
    }

    private fun setupRecyclerViews() {
        channelAnalysisAdapter = ChannelAnalysisAdapter()
        recommendationAdapter = ChannelRecommendationAdapter()

        channelAnalysisAdapter.setOnLongClickListener { channelData ->
            showExclusionDialog(channelData)
        }

        binding.recyclerViewChannels.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = channelAnalysisAdapter
        }

        binding.recyclerViewRecommendations.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = recommendationAdapter
        }
    }

    private fun setupBandToggle() {
        binding.bandToggleGroup.check(R.id.button2GHz)

        binding.bandToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val band = when (checkedId) {
                    R.id.button5GHz -> FrequencyBand.GHZ_5
                    R.id.button6GHz -> FrequencyBand.GHZ_6
                    else -> FrequencyBand.GHZ_2_4
                }
                viewModel.setBand(band)
                updateSpectrumView()
            }
        }
    }

    private fun setupClickListeners() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            performScan()
        }

        binding.fabRefresh.setOnClickListener {
            performScan()
        }

        binding.buttonStartScan.setOnClickListener {
            performScan()
        }
    }

    private fun observeViewModel() {
        viewModel.analysisData.observe(viewLifecycleOwner) { analysis ->
            updateUI(analysis)
            updateSpectrumView()
        }

        viewModel.channelAnalysis.observe(viewLifecycleOwner) { channels ->
            channelAnalysisAdapter.submitList(channels)
        }

        viewModel.recommendations.observe(viewLifecycleOwner) { recommendations ->
            recommendationAdapter.submitList(recommendations)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.swipeRefreshLayout.isRefreshing = isLoading
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }
    }

    private fun observeWifiScannerViewModel() {
        wifiScannerViewModel.wifiList.observe(viewLifecycleOwner) { wifiList ->
            if (wifiList.isNotEmpty()) {
                viewModel.refreshAnalysisFromExistingData(wifiList)
                hideEmptyState()
            } else {
                showEmptyState()
            }
        }

        wifiScannerViewModel.scanState.observe(viewLifecycleOwner) { message ->
            if (message.contains("failed") || message.contains("error")) {
                binding.swipeRefreshLayout.isRefreshing = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUI(analysis: WiFiEnvironmentAnalysis?) {
        if (analysis == null) {
            showEmptyState()
            return
        }

        hideEmptyState()

        binding.textViewTotalNetworks.text = getString(R.string.total_networks, analysis.totalNetworks)
        binding.textViewUniqueChannels.text = getString(R.string.unique_channels, analysis.uniqueChannels)
        binding.textViewAverageSignal.text = getString(R.string.average_signal, analysis.averageSignalStrength)
    }

    private fun updateSpectrumView() {
        val networks = wifiScannerViewModel.wifiList.value ?: emptyList()
        val excludedBssids = viewModel.excludedBssids.value ?: emptySet()
        val selectedBand = viewModel.selectedBand.value ?: FrequencyBand.GHZ_2_4

        binding.wifiSpectrumView.updateSpectrumData(networks, excludedBssids, selectedBand)
    }

    private fun showEmptyState() {
        binding.layoutEmptyState.visibility = View.VISIBLE
        binding.swipeRefreshLayout.visibility = View.GONE
        binding.fabRefresh.visibility = View.GONE
    }

    private fun hideEmptyState() {
        binding.layoutEmptyState.visibility = View.GONE
        binding.swipeRefreshLayout.visibility = View.VISIBLE
        binding.fabRefresh.visibility = View.VISIBLE
    }

    private fun performScan() {
        if (hasLocationPermission()) {
            startWifiScan()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startWifiScan() {
        binding.swipeRefreshLayout.isRefreshing = true
        wifiScannerViewModel.startWifiScan()
    }

    private fun checkInitialData() {
        val existingData = wifiScannerViewModel.wifiList.value
        if (!existingData.isNullOrEmpty()) {
            viewModel.refreshAnalysisFromExistingData(existingData)
            hideEmptyState()
        } else {
            showEmptyState()
        }
    }

    private fun showExclusionDialog(channelData: ChannelAnalysisData) {
        if (channelData.networks.isEmpty()) return

        val networkNames = channelData.networks.map { "${it.scanResult.SSID} (${it.scanResult.BSSID})" }
        val excludedBssids = viewModel.excludedBssids.value ?: emptySet()
        val checkedItems = channelData.networks.map { excludedBssids.contains(it.scanResult.BSSID) }.toBooleanArray()

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.exclude_networks))
            .setMultiChoiceItems(networkNames.toTypedArray(), checkedItems) { _, which, isChecked ->
                val bssid = channelData.networks[which].scanResult.BSSID
                if (excludedBssids.contains(bssid) != isChecked) {
                    viewModel.toggleBssidExclusion(bssid)
                }
            }
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()

        val existingData = wifiScannerViewModel.wifiList.value
        if (!existingData.isNullOrEmpty()) {
            viewModel.refreshAnalysisFromExistingData(existingData)
            updateSpectrumView()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}