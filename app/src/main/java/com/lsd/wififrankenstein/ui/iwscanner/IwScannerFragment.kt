package com.lsd.wififrankenstein.ui.iwscanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentIwScannerBinding

class IwScannerFragment : Fragment() {

    private var _binding: FragmentIwScannerBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<IwScannerViewModel>()
    private lateinit var networkAdapter: IwNetworkAdapter
    private lateinit var interfaceAdapter: InterfaceSpinnerAdapter

    private var isDeviceInfoExpanded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIwScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSpinner()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        networkAdapter = IwNetworkAdapter { network ->
            showNetworkDetails(network)
        }

        binding.recyclerViewNetworks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = networkAdapter
        }
    }

    private fun setupSpinner() {
        interfaceAdapter = InterfaceSpinnerAdapter(requireContext(), emptyList())
        binding.spinnerInterface.adapter = interfaceAdapter

        binding.spinnerInterface.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedInterface = interfaceAdapter.getItem(position)
                viewModel.setSelectedInterface(selectedInterface.name)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupClickListeners() {
        binding.buttonScan.setOnClickListener {
            viewModel.scanNetworks()
        }

        binding.buttonRefreshLink.setOnClickListener {
            viewModel.refreshLinkInfo()
        }

        binding.buttonRefreshDevice.setOnClickListener {
            viewModel.refreshDeviceInfo()
        }

        binding.buttonExpandDevice.setOnClickListener {
            toggleDeviceInfo()
        }

        binding.buttonRefreshInterfaces.setOnClickListener {
            viewModel.refreshInterfaces()
        }

        binding.buttonManualInterface.setOnClickListener {
            showManualInterfaceDialog()
        }
    }

    private fun observeViewModel() {
        viewModel.scanState.observe(viewLifecycleOwner) { state ->
            updateScanUI(state)
        }

        viewModel.networks.observe(viewLifecycleOwner) { networks ->
            networkAdapter.submitList(networks)
            binding.textEmptyNetworks.visibility = if (networks.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerViewNetworks.visibility = if (networks.isNotEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.linkInfo.observe(viewLifecycleOwner) { linkInfo ->
            updateLinkInfo(linkInfo)
        }

        viewModel.deviceInfo.observe(viewLifecycleOwner) { deviceInfo ->
            updateDeviceInfo(deviceInfo)
        }

        viewModel.rootAccessAvailable.observe(viewLifecycleOwner) { hasRoot ->
            if (!hasRoot) {
                showRootRequiredDialog()
            }
        }

        viewModel.progressMessage.observe(viewLifecycleOwner) { message ->
            binding.textScanStatus.text = message
        }

        viewModel.availableInterfaces.observe(viewLifecycleOwner) { interfaces: List<IwInterface> ->
            interfaceAdapter.updateInterfaces(interfaces)

            val currentSelected = viewModel.selectedInterface.value ?: "wlan0"
            val selectedIndex = interfaces.indexOfFirst { it.name == currentSelected }
            if (selectedIndex >= 0) {
                binding.spinnerInterface.setSelection(selectedIndex)
            }
        }

        viewModel.selectedInterface.observe(viewLifecycleOwner) { interfaceName: String ->
            val interfaces = viewModel.availableInterfaces.value ?: emptyList()
            val selectedIndex = interfaces.indexOfFirst { it.name == interfaceName }
            if (selectedIndex >= 0 && binding.spinnerInterface.selectedItemPosition != selectedIndex) {
                binding.spinnerInterface.setSelection(selectedIndex)
            }
        }
    }

    private fun updateScanUI(state: IwScanState) {
        when (state) {
            is IwScanState.Idle -> {
                binding.buttonScan.isEnabled = true
                binding.progressBarScan.visibility = View.GONE
                binding.buttonScan.text = getString(R.string.iw_scan_networks)
            }
            is IwScanState.Scanning -> {
                binding.buttonScan.isEnabled = false
                binding.progressBarScan.visibility = View.VISIBLE
                binding.buttonScan.text = getString(R.string.iw_scanning)
            }
            is IwScanState.LoadingDeviceInfo -> {
                binding.buttonRefreshDevice.isEnabled = false
                binding.textDeviceStatus.text = getString(R.string.iw_loading_device_info)
            }
            is IwScanState.Completed -> {
                binding.buttonScan.isEnabled = true
                binding.progressBarScan.visibility = View.GONE
                binding.buttonScan.text = getString(R.string.iw_scan_networks)
            }
            is IwScanState.Failed -> {
                binding.buttonScan.isEnabled = true
                binding.progressBarScan.visibility = View.GONE
                binding.buttonScan.text = getString(R.string.iw_scan_networks)
                showErrorDialog(state.error)
            }
        }
    }

    private fun updateLinkInfo(linkInfo: IwLinkInfo) {
        if (linkInfo.connected) {
            binding.textLinkStatus.text = getString(R.string.iw_connected_to, linkInfo.ssid)
            binding.layoutLinkDetails.visibility = View.VISIBLE

            binding.textLinkSSID.text = getString(R.string.iw_ssid_format, linkInfo.ssid)
            binding.textLinkBSSID.text = getString(R.string.iw_bssid_format, linkInfo.bssid)

            val details = mutableListOf<String>()
            if (linkInfo.frequency.isNotBlank()) details.add(getString(R.string.iw_freq_format, linkInfo.frequency))
            if (linkInfo.signal.isNotBlank()) details.add(getString(R.string.iw_signal_format, linkInfo.signal))
            if (linkInfo.txBitrate.isNotBlank()) details.add(getString(R.string.iw_bitrate_format, linkInfo.txBitrate))

            binding.textLinkDetails.text = details.joinToString(" • ")
        } else {
            binding.textLinkStatus.text = getString(R.string.iw_not_connected)
            binding.layoutLinkDetails.visibility = View.GONE
        }
    }

    private fun updateDeviceInfo(deviceInfo: IwDeviceInfo) {
        binding.buttonRefreshDevice.isEnabled = true

        if (deviceInfo.wiphy.isNotBlank()) {
            binding.textDeviceStatus.text = deviceInfo.wiphy

            if (isDeviceInfoExpanded) {
                val details = StringBuilder()
                details.append("${deviceInfo.wiphy}\n")

                deviceInfo.capabilities?.let { caps ->
                    if (caps.wiphyIndex.isNotBlank()) {
                        details.append("${getString(R.string.iw_device_wiphy_index)}: ${caps.wiphyIndex}\n")
                    }
                    if (caps.maxScanSSIDs.isNotBlank()) {
                        details.append("${getString(R.string.iw_device_max_scan_ssids)}: ${caps.maxScanSSIDs}\n")
                    }
                    if (caps.maxScanIEsLength.isNotBlank()) {
                        details.append("${getString(R.string.iw_device_max_scan_ies_length)}: ${caps.maxScanIEsLength}\n")
                    }
                    if (caps.coverageClass.isNotBlank()) {
                        details.append("${getString(R.string.iw_device_coverage_class)}: ${caps.coverageClass}\n")
                    }
                    if (caps.supportsTDLS) {
                        details.append("${getString(R.string.iw_device_supports_tdls)}\n")
                    }
                    if (caps.availableAntennas.isNotBlank()) {
                        details.append("${getString(R.string.iw_device_available_antennas)}: ${caps.availableAntennas}\n")
                    }
                    details.append("\n")

                    if (caps.supportedInterfaceModes.isNotEmpty()) {
                        details.append("${getString(R.string.iw_device_supported_interface_modes)}:\n")
                        caps.supportedInterfaceModes.take(10).forEach { mode ->
                            details.append("  • $mode\n")
                        }
                        if (caps.supportedInterfaceModes.size > 10) {
                            details.append("  ${getString(R.string.iw_device_and_more, caps.supportedInterfaceModes.size - 10)}\n")
                        }
                        details.append("\n")
                    }

                    if (caps.supportedCiphers.isNotEmpty()) {
                        details.append("${getString(R.string.iw_device_supported_ciphers)}:\n")
                        caps.supportedCiphers.take(8).forEach { cipher ->
                            details.append("  • $cipher\n")
                        }
                        if (caps.supportedCiphers.size > 8) {
                            details.append("  ${getString(R.string.iw_device_and_more, caps.supportedCiphers.size - 8)}\n")
                        }
                        details.append("\n")
                    }
                }

                deviceInfo.bands.forEachIndexed { index, band ->
                    details.append("${band.bandNumber}\n")

                    if (band.capabilities.value.isNotBlank()) {
                        details.append("  ${getString(R.string.iw_device_capabilities)}: ${band.capabilities.value}\n")
                    }

                    if (band.capabilities.htSupport.isNotEmpty()) {
                        details.append("  ${getString(R.string.iw_device_ht_support)}:\n")
                        band.capabilities.htSupport.take(5).forEach { ht ->
                            details.append("    • $ht\n")
                        }
                        if (band.capabilities.htSupport.size > 5) {
                            details.append("    ${getString(R.string.iw_device_and_more, band.capabilities.htSupport.size - 5)}\n")
                        }
                    }

                    if (band.frequencies.isNotEmpty()) {
                        details.append("  ${getString(R.string.iw_device_frequencies)} (${band.frequencies.size}):\n")
                        band.frequencies.take(6).forEach { freq ->
                            details.append("    • ${freq.frequency} MHz [${freq.channel}] (${freq.power})")
                            if (freq.flags.isNotEmpty()) {
                                details.append(" ${freq.flags.joinToString(" ")}")
                            }
                            details.append("\n")
                        }
                        if (band.frequencies.size > 6) {
                            details.append("    ${getString(R.string.iw_device_and_more, band.frequencies.size - 6)}\n")
                        }
                    }

                    if (band.bitrates.isNotEmpty()) {
                        details.append("  ${getString(R.string.iw_device_bitrates)} (${band.bitrates.size}):\n")
                        band.bitrates.take(6).forEach { rate ->
                            details.append("    • ${rate.rate} Mbps")
                            if (rate.flags.isNotEmpty()) {
                                details.append(" (${rate.flags.joinToString(", ")})")
                            }
                            details.append("\n")
                        }
                        if (band.bitrates.size > 6) {
                            details.append("    ${getString(R.string.iw_device_and_more, band.bitrates.size - 6)}\n")
                        }
                    }

                    if (index < deviceInfo.bands.size - 1) {
                        details.append("\n")
                    }
                }

                binding.textDeviceDetails.text = details.toString().trim()
            }
        } else {
            binding.textDeviceStatus.text = getString(R.string.iw_device_info_unavailable)
        }
    }

    private fun toggleDeviceInfo() {
        isDeviceInfoExpanded = !isDeviceInfoExpanded

        if (isDeviceInfoExpanded) {
            binding.textDeviceDetails.visibility = View.VISIBLE
            binding.buttonExpandDevice.setImageResource(R.drawable.ic_expand_less)
            viewModel.deviceInfo.value?.let { updateDeviceInfo(it) }
        } else {
            binding.textDeviceDetails.visibility = View.GONE
            binding.buttonExpandDevice.setImageResource(R.drawable.ic_expand_more)
        }
    }

    private fun showNetworkDetails(network: IwNetworkInfo) {
        val details = StringBuilder()

        details.append("BSSID: ${network.bssid}\n")
        details.append("SSID: ${if (network.ssid.isNotBlank()) network.ssid else getString(R.string.iw_hidden_ssid)}\n")
        details.append("Frequency: ${network.frequency} MHz\n")
        details.append("Channel: ${network.channel}\n")
        details.append("Signal: ${network.signal}\n")
        details.append("Capability: ${network.capability}\n")
        details.append("Last seen: ${network.lastSeen}\n")
        if (network.isAssociated) details.append("Status: Connected\n")

        if (network.supportedRates.isNotEmpty()) {
            details.append("\nSupported rates: ${network.supportedRates.joinToString(" ")}\n")
        }

        if (network.extendedRates.isNotEmpty()) {
            details.append("Extended rates: ${network.extendedRates.joinToString(" ")}\n")
        }

        if (network.country.isNotBlank()) {
            details.append("Country: ${network.country}\n")
        }

        if (network.security.wpa.isNotBlank()) {
            details.append("\nWPA:\n${network.security.wpa}\n")
        }

        if (network.security.rsn.isNotBlank()) {
            details.append("\nRSN:\n${network.security.rsn}\n")
        }

        if (network.security.wps.isNotBlank()) {
            details.append("\nWPS:\n${network.security.wps}\n")
        }

        if (network.capabilities.htCapabilities.isNotBlank()) {
            details.append("\nHT Capabilities:\n${network.capabilities.htCapabilities}\n")
        }

        if (network.capabilities.vhtCapabilities.isNotBlank()) {
            details.append("\nVHT Capabilities:\n${network.capabilities.vhtCapabilities}\n")
        }

        if (network.capabilities.heCapabilities.isNotBlank()) {
            details.append("\nHE Capabilities:\n${network.capabilities.heCapabilities}\n")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (network.ssid.isNotBlank()) network.ssid else network.bssid)
            .setMessage(details.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showManualInterfaceDialog() {
        val editText = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.iw_interface_hint)
            setText(viewModel.selectedInterface.value ?: "wlan0")
            selectAll()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.iw_manual_interface_title)
            .setMessage(R.string.iw_manual_interface_message)
            .setView(editText)
            .setPositiveButton(R.string.iw_set_interface) { _, _ ->
                val interfaceName = editText.text.toString().trim()
                if (interfaceName.isNotBlank()) {
                    val currentInterfaces: MutableList<IwInterface> = viewModel.availableInterfaces.value?.toMutableList() ?: mutableListOf()

                    if (currentInterfaces.none { it.name == interfaceName }) {
                        currentInterfaces.add(IwInterface(interfaceName, "manual", ""))
                        interfaceAdapter.updateInterfaces(currentInterfaces)
                    }

                    viewModel.setSelectedInterface(interfaceName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRootRequiredDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.iw_root_required_title)
            .setMessage(R.string.iw_root_required_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showErrorDialog(error: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.iw_error_title)
            .setMessage(getString(R.string.iw_error_message, error))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}