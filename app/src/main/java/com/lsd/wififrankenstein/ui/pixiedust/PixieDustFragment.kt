package com.lsd.wififrankenstein.ui.pixiedust

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputFilter
import com.lsd.wififrankenstein.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentPixiedustBinding
import com.lsd.wififrankenstein.ui.iwscanner.InterfaceSpinnerAdapter
import com.lsd.wififrankenstein.ui.iwscanner.IwInterface
import java.util.regex.Pattern

class PixieDustFragment : Fragment() {

    private var _binding: FragmentPixiedustBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<PixieDustViewModel>()
    private lateinit var networkAdapter: WpsNetworkAdapter
    private lateinit var logAdapter: LogAdapter

    private var useAggressiveCleanup = false

    private lateinit var interfaceAdapter: InterfaceSpinnerAdapter

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.scanForWpsNetworks()
        } else {
            Toast.makeText(
                requireContext(),
                R.string.permission_required_location,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPixiedustBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupClickListeners()
        observeViewModel()
        setupInterfaceSpinner()
        showWarningDialog()
    }

    private fun setupRecyclerViews() {
        networkAdapter = WpsNetworkAdapter { network ->
            viewModel.selectNetwork(network)
        }

        binding.recyclerViewNetworks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = networkAdapter
        }

        logAdapter = LogAdapter()
        binding.recyclerViewLog.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                reverseLayout = true
                stackFromEnd = true
            }
            adapter = logAdapter
        }
    }

    private fun setupClickListeners() {
        binding.layoutAdvancedHeader.setOnClickListener {
            toggleAdvancedSettings()
        }

        binding.buttonRefreshInterfaces.setOnClickListener {
            viewModel.refreshInterfaces()
        }

        binding.buttonManualInterface.setOnClickListener {
            showManualInterfaceDialog()
        }

        binding.switchAggressiveCleanup.setOnCheckedChangeListener { _, isChecked ->
            useAggressiveCleanup = isChecked
            viewModel.setAggressiveCleanup(isChecked)
        }

        binding.sliderExtractionTimeout.addOnChangeListener { _, value, _ ->
            binding.textExtractionTimeout.text = value.toInt().toString()
            viewModel.setExtractionTimeout(value.toLong() * 1000)
        }

        binding.sliderComputationTimeout.addOnChangeListener { _, value, _ ->
            binding.textComputationTimeout.text = value.toInt().toString()
            viewModel.setComputationTimeout(value.toLong() * 1000)
        }

        binding.buttonResetDefaults.setOnClickListener {
            resetToDefaults()
        }

        binding.buttonCleanupBinaries.setOnClickListener {
            showCleanupConfirmationDialog()
        }

        binding.buttonCopyBinaries.setOnClickListener {
            viewModel.copyBinariesManually()
        }

        binding.buttonScanNetworks.setOnClickListener {
            checkLocationPermissionAndScan()
        }

        binding.buttonManualNetwork.setOnClickListener {
            showManualNetworkDialog()
        }

        binding.buttonStartAttack.setOnClickListener {
            viewModel.startPixieAttack()
        }

        binding.buttonStopAttack.setOnClickListener {
            viewModel.stopAttack()
        }

        binding.buttonCopyPin.setOnClickListener {
            val state = viewModel.attackState.value
            if (state is PixieAttackState.Completed && state.result.pin != null) {
                copyToClipboard(state.result.pin)
            }
        }

        binding.buttonSaveResult.setOnClickListener {
            val state = viewModel.attackState.value
            if (state is PixieAttackState.Completed) {
                viewModel.saveResult(state.result)
            }
        }

        binding.buttonClearLog.setOnClickListener {
            viewModel.clearLog()
        }
    }

    private fun setupInterfaceSpinner() {
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

    private fun showCleanupConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pixiedust_cleanup_binaries_title)
            .setMessage(R.string.pixiedust_cleanup_binaries_message)
            .setPositiveButton(R.string.pixiedust_cleanup_confirm) { _, _ ->
                viewModel.cleanupBinaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleAdvancedSettings() {
        val isExpanded = binding.layoutAdvancedContent.visibility == View.VISIBLE

        if (isExpanded) {
            binding.layoutAdvancedContent.visibility = View.GONE
            binding.iconAdvancedExpand.setImageResource(R.drawable.ic_expand_more)
        } else {
            binding.layoutAdvancedContent.visibility = View.VISIBLE
            binding.iconAdvancedExpand.setImageResource(R.drawable.ic_expand_less)
        }
    }

    private fun resetToDefaults() {
        binding.switchAggressiveCleanup.isChecked = false
        useAggressiveCleanup = false
        viewModel.setAggressiveCleanup(false)

        binding.sliderExtractionTimeout.value = 30f
        binding.sliderComputationTimeout.value = 300f

        binding.textExtractionTimeout.text = "30"
        binding.textComputationTimeout.text = "300"

        viewModel.setExtractionTimeout(30000)
        viewModel.setComputationTimeout(300000)
    }

    private fun observeViewModel() {
        viewModel.wpsNetworks.observe(viewLifecycleOwner) { networks ->
            networkAdapter.submitList(networks)
            binding.cardViewNetworks.visibility = if (networks.isNotEmpty()) View.VISIBLE else View.GONE

            if (networks.isEmpty() && viewModel.isScanning.value == false) {
                binding.textViewScanStatus.text = getString(R.string.pixiedust_no_networks)
            }
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

        viewModel.isScanning.observe(viewLifecycleOwner) { isScanning ->
            binding.buttonScanNetworks.isEnabled = !isScanning
            if (isScanning) {
                binding.progressBarScanning.startAnimation()
            } else {
                binding.progressBarScanning.stopAnimation()
            }
        }

        viewModel.selectedNetwork.observe(viewLifecycleOwner) { network ->
            networkAdapter.setSelectedNetwork(network)
            binding.cardViewAttackControl.visibility = if (network != null) View.VISIBLE else View.GONE

            if (network != null) {
                val networkName = if (network.ssid.isBlank()) getString(R.string.pixiedust_unknown_network) else network.ssid
                binding.textViewSelectedNetwork.text = getString(
                    R.string.pixiedust_network_info,
                    networkName,
                    network.bssid
                )
            }

            updateSystemStatus()
        }

        viewModel.attackState.observe(viewLifecycleOwner) { state ->
            updateAttackUI(state)
        }

        viewModel.progressMessage.observe(viewLifecycleOwner) { message ->
            binding.textViewScanStatus.text = message
            binding.textViewAttackStatus.text = message
        }

        viewModel.rootAccessAvailable.observe(viewLifecycleOwner) { hasRoot ->
            updateSystemStatus()
        }

        viewModel.binariesReady.observe(viewLifecycleOwner) { ready ->
            updateSystemStatus()
        }

        viewModel.isCopyingBinaries.observe(viewLifecycleOwner) { isCopying ->
            binding.buttonCopyBinaries.isEnabled = !isCopying
            binding.buttonCopyBinaries.text = if (isCopying) {
                getString(R.string.pixiedust_copying_binaries)
            } else {
                getString(R.string.pixiedust_copy_binaries)
            }
        }

        viewModel.isCleaningBinaries.observe(viewLifecycleOwner) { isCleaning ->
            binding.buttonCleanupBinaries.isEnabled = !isCleaning
            binding.buttonCleanupBinaries.text = if (isCleaning) {
                getString(R.string.pixiedust_cleaning_binaries)
            } else {
                getString(R.string.pixiedust_cleanup_binaries)
            }
        }

        viewModel.logEntries.observe(viewLifecycleOwner) { logEntries ->
            logAdapter.submitList(logEntries) {
                if (logEntries.isNotEmpty()) {
                    binding.recyclerViewLog.scrollToPosition(0)
                }
            }
            binding.recyclerViewLog.visibility = if (logEntries.isNotEmpty()) View.VISIBLE else View.GONE
            binding.textViewLogEmpty.visibility = if (logEntries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updateSystemStatus() {
        val hasRoot = viewModel.rootAccessAvailable.value ?: false
        val hasBinaries = viewModel.binariesReady.value ?: false
        val selectedNetwork = viewModel.selectedNetwork.value
        val hasSelectedNetwork = selectedNetwork != null
        val attackState = viewModel.attackState.value

        binding.textViewRootStatus.text = if (hasRoot) {
            getString(R.string.pixiedust_root_available)
        } else {
            getString(R.string.pixiedust_root_not_available)
        }

        binding.iconRootStatus.setImageResource(
            if (hasRoot) R.drawable.ic_check else R.drawable.ic_close
        )

        binding.textViewBinaryStatus.text = if (hasBinaries) {
            getString(R.string.pixiedust_binary_files_ready)
        } else {
            getString(R.string.pixiedust_binary_files_not_available)
        }

        binding.iconBinaryStatus.setImageResource(
            if (hasBinaries) R.drawable.ic_check else R.drawable.ic_close
        )

        binding.buttonCopyBinaries.visibility = if (hasBinaries) View.GONE else View.VISIBLE

        val canAttack = hasRoot && hasBinaries && hasSelectedNetwork
        val isIdle = attackState is PixieAttackState.Idle

        binding.buttonStartAttack.isEnabled = canAttack && isIdle

        Log.d("PixieDustFragment", "System status - Root: $hasRoot, Binaries: $hasBinaries, Selected: $hasSelectedNetwork, CanAttack: $canAttack")
    }

    private fun showManualInterfaceDialog() {
        val editText = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.pixiedust_interface_hint)
            setText(viewModel.selectedInterface.value ?: "wlan0")
            selectAll()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pixiedust_manual_interface_title)
            .setMessage(R.string.pixiedust_manual_interface_message)
            .setView(editText)
            .setPositiveButton(R.string.pixiedust_set_interface) { _, _ ->
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

    private fun updateAttackUI(state: PixieAttackState) {
        when (state) {
            is PixieAttackState.Idle -> {
                updateSystemStatus()
                binding.buttonStopAttack.isEnabled = false
                binding.progressBarAttack.stopAnimation()
                binding.cardViewResult.visibility = View.GONE
            }

            is PixieAttackState.Scanning,
            is PixieAttackState.CheckingRoot,
            is PixieAttackState.Preparing,
            is PixieAttackState.ExtractingData,
            is PixieAttackState.RunningAttack -> {
                binding.buttonStartAttack.isEnabled = false
                binding.buttonStopAttack.isEnabled = true
                binding.progressBarAttack.startAnimation()
                binding.cardViewResult.visibility = View.GONE
            }

            is PixieAttackState.Completed -> {
                binding.buttonStopAttack.isEnabled = false
                binding.progressBarAttack.stopAnimation()
                binding.cardViewResult.visibility = View.VISIBLE

                val result = state.result
                if (result.success && result.pin != null) {
                    binding.textViewResult.text = getString(R.string.pixiedust_pin_found, result.pin)
                    binding.layoutResultActions.visibility = View.VISIBLE
                } else {
                    binding.textViewResult.text = getString(R.string.pixiedust_pin_not_found)
                    binding.layoutResultActions.visibility = View.GONE
                }

                updateSystemStatus()
            }

            is PixieAttackState.Failed -> {
                binding.buttonStopAttack.isEnabled = false
                binding.progressBarAttack.stopAnimation()
                binding.cardViewResult.visibility = View.VISIBLE
                binding.textViewResult.text = getString(R.string.pixiedust_attack_failed, state.error)
                binding.layoutResultActions.visibility = View.GONE

                updateSystemStatus()

                if (state.errorCode == -4) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Attack Failed")
                        .setMessage("Could not extract WPS handshake data. The target network may not be vulnerable or WPS may be disabled.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun showManualNetworkDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_manual_network, null)
        val ssidEdit = view.findViewById<EditText>(R.id.editTextSSID)
        val bssidEdit = view.findViewById<EditText>(R.id.editTextBSSID)

        bssidEdit.filters = arrayOf(
            InputFilter.AllCaps(),
            InputFilter.LengthFilter(17)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pixiedust_manual_network_entry)
            .setView(view)
            .setPositiveButton(R.string.pixiedust_add_network) { _, _ ->
                val ssid = ssidEdit.text.toString().trim()
                val bssid = bssidEdit.text.toString().trim()

                if (ssid.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.pixiedust_ssid_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (!isValidBSSID(bssid)) {
                    Toast.makeText(requireContext(), R.string.pixiedust_invalid_bssid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                viewModel.addManualNetwork(ssid, bssid)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isValidBSSID(bssid: String): Boolean {
        val pattern = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
        return pattern.matcher(bssid).matches()
    }

    private fun checkLocationPermissionAndScan() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                viewModel.scanForWpsNetworks()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.permission_required)
                    .setMessage(R.string.location_permission_rationale)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            else -> {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun showWarningDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pixiedust_warning_title)
            .setMessage(R.string.pixiedust_warning_message)
            .setPositiveButton(R.string.pixiedust_understand_risks, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("PixieDust PIN", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), R.string.pixiedust_copy_pin, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}