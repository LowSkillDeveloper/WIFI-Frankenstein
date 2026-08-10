package com.lsd.wififrankenstein.ui.wpagenerator

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentWpaGeneratorBinding
import com.lsd.wififrankenstein.ui.updates.UpdatesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WpaGeneratorFragment : Fragment() {

    private var _binding: FragmentWpaGeneratorBinding? = null
    private val binding get() = _binding!!

    private lateinit var wpaHelper: WpaAlgorithmsHelper
    private lateinit var wifiManager: WifiManager
    private lateinit var networksAdapter: NetworksAdapter
    private val viewModel: UpdatesViewModel by activityViewModels()

    private val locationPermissionCode = 1001
    private var currentMode = MODE_MANUAL
    private var isScanning = false

    private fun setupDicBanner() {
        binding.buttonDownloadDic.setOnClickListener {
            viewModel.downloadRouterKeygenDic()
        }
        binding.buttonCancelDicDownload.setOnClickListener {
            viewModel.cancelDownload(UpdatesViewModel.ROUTER_KEYGEN_FILE_NAME)
        }
    }

    private fun observeDicStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.routerKeygenInfo.collectLatest { info ->
                        if (info == null) {
                            val dicFile = File(
                                requireContext().filesDir,
                                UpdatesViewModel.ROUTER_KEYGEN_FILE_NAME
                            )
                            binding.cardViewDicMissing.visibility =
                                if (dicFile.exists()) View.GONE else View.VISIBLE
                            return@collectLatest
                        }
                        if (info.installed) {
                            binding.cardViewDicMissing.visibility = View.GONE
                        } else if (info.isDownloading) {
                            binding.cardViewDicMissing.visibility = View.VISIBLE
                            binding.buttonDownloadDic.visibility = View.GONE
                            binding.buttonCancelDicDownload.visibility = View.VISIBLE
                            binding.progressBarDicDownload.visibility = View.VISIBLE
                            binding.progressBarDicDownload.progress = info.downloadProgress
                        } else {
                            binding.cardViewDicMissing.visibility = View.VISIBLE
                            binding.buttonDownloadDic.visibility = View.VISIBLE
                            binding.buttonCancelDicDownload.visibility = View.GONE
                            binding.progressBarDicDownload.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val MODE_MANUAL = 0
        private const val MODE_SCAN = 1
        private val BSSID_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWpaGeneratorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        wpaHelper = WpaAlgorithmsHelper(requireContext())
        wifiManager = requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager

        setupUI()
        setupAdapters()
        setupListeners()
        setupDicBanner()
        observeDicStatus()
    }

    private fun setupUI() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.network_scan_mode))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.manual_input_mode))

        currentMode = MODE_SCAN
        showScanMode()
    }

    private fun setupAdapters() {
        networksAdapter = NetworksAdapter(
            onNetworkClick = { network ->
                generateKeysForNetwork(network)
            },
            onKeyClick = { key ->
                copyToClipboard(key)
            }
        )

        binding.networksRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = networksAdapter
        }
    }

    private fun setupListeners() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        currentMode = MODE_SCAN
                        showScanMode()
                    }

                    1 -> {
                        currentMode = MODE_MANUAL
                        showManualInputMode()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.generateButton.setOnClickListener {
            if (currentMode == MODE_MANUAL) {
                generateKeysManually()
            } else {
                startNetworkScan()
            }
        }
    }

    private fun showManualInputMode() {
        binding.manualInputGroup.visibility = View.VISIBLE
        binding.generateButton.text = getString(R.string.generate_keys)
        binding.generateButton.setEnabled(true)
        binding.networksRecyclerView.visibility = View.GONE
        binding.statusText.text = ""
    }

    private fun showScanMode() {
        binding.manualInputGroup.visibility = View.GONE
        binding.generateButton.text = getString(R.string.scan_networks)
        binding.generateButton.setEnabled(true)
        binding.networksRecyclerView.visibility = View.VISIBLE
        binding.statusText.text = ""
    }

    private fun generateKeysManually() {
        val ssid = binding.ssidEditText.text.toString().trim()
        val bssid = binding.bssidEditText.text.toString().trim()

        if (ssid.isEmpty() || bssid.isEmpty()) {
            Toast.makeText(context, R.string.invalid_input, Toast.LENGTH_SHORT).show()
            return
        }

        if (!isValidBssid(bssid)) {
            Toast.makeText(context, R.string.invalid_input, Toast.LENGTH_SHORT).show()
            return
        }

        val network = NetworkInfo(ssid, bssid, 0, wpaHelper.getSupportState(ssid, bssid))
        networksAdapter.updateNetworks(listOf(network))
        binding.networksRecyclerView.visibility = View.VISIBLE
        generateKeysForNetwork(network)
    }

    private fun generateKeysForNetwork(network: NetworkInfo) {
        binding.progressBar.startAnimation()
        binding.generateButton.isEnabled = false
        binding.statusText.text = getString(R.string.generating_keys)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    wpaHelper.generateKeys(network.ssid, network.bssid)
                }

                binding.progressBar.stopAnimation()
                binding.generateButton.isEnabled = true

                if (results.isEmpty()) {
                    binding.statusText.text = getString(R.string.no_algorithms_supported)
                } else {
                    val totalKeys = results.sumOf { it.keys.size }
                    binding.statusText.text = getString(R.string.generated_keys) + ": $totalKeys"
                    networksAdapter.updateNetworkResults(network.bssid, results)
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.progressBar.stopAnimation()
                binding.generateButton.isEnabled = true
                binding.statusText.text =
                    getString(R.string.error_general, e.message ?: "Unknown error")
            }
        }
    }

    private fun startNetworkScan() {
        if (!checkLocationPermission()) {
            requestLocationPermission()
            return
        }

        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(context, R.string.wifi_disabled, Toast.LENGTH_SHORT).show()
            return
        }

        if (isScanning) return

        isScanning = true
        binding.progressBar.startAnimation()
        binding.generateButton.isEnabled = false
        binding.statusText.text = getString(R.string.scanning_networks)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    wifiManager.startScan()
                }

                if (success) {
                    kotlinx.coroutines.delay(3000)

                    val scanResults = wifiManager.scanResults
                    val networks = scanResults.mapNotNull { result ->
                        if (result.SSID.isNotEmpty()) {
                            val supportState = wpaHelper.getSupportState(result.SSID, result.BSSID)
                            NetworkInfo(result.SSID, result.BSSID, result.level, supportState)
                        } else null
                    }.distinctBy { it.bssid }

                    isScanning = false
                    binding.progressBar.stopAnimation()
                    binding.generateButton.isEnabled = true

                    if (networks.isEmpty()) {
                        binding.statusText.text = getString(R.string.no_networks_found)
                    } else {
                        binding.statusText.text =
                            getString(R.string.networks_scanned, networks.size)
                        networksAdapter.updateNetworks(networks)
                    }
                } else {
                    isScanning = false
                    binding.progressBar.stopAnimation()
                    binding.generateButton.isEnabled = true
                    binding.statusText.text = getString(R.string.failed_to_start_wifi_scan)
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                isScanning = false
                binding.progressBar.stopAnimation()
                binding.generateButton.isEnabled = true
                binding.statusText.text = getString(R.string.error_scanning_wifi)
            }
        }
    }

    private fun isValidBssid(bssid: String): Boolean {
        return bssid.matches(BSSID_REGEX)
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            locationPermissionCode
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startNetworkScan()
            } else {
                Toast.makeText(context, R.string.permission_required_location, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("WPA Key", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, R.string.keys_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class NetworkInfo(
        val ssid: String,
        val bssid: String,
        val level: Int,
        val supportState: Int
    )
}