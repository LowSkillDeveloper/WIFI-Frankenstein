package com.lsd.wififrankenstein.ui.pixiedust

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentPixiedustBinding

class PixieDustFragment : Fragment() {

    private var _binding: FragmentPixiedustBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<PixieDustViewModel>()
    private lateinit var networkAdapter: WpsNetworkAdapter

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

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
        showWarningDialog()
    }

    private fun setupRecyclerView() {
        networkAdapter = WpsNetworkAdapter { network ->
            viewModel.selectNetwork(network)
        }

        binding.recyclerViewNetworks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = networkAdapter
        }
    }

    private fun setupClickListeners() {
        binding.buttonScanNetworks.setOnClickListener {
            checkLocationPermissionAndScan()
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
    }

    private fun observeViewModel() {
        viewModel.wpsNetworks.observe(viewLifecycleOwner) { networks ->
            networkAdapter.submitList(networks)
            binding.cardViewNetworks.visibility = if (networks.isNotEmpty()) View.VISIBLE else View.GONE

            if (networks.isEmpty() && !viewModel.isScanning.value!!) {
                binding.textViewScanStatus.text = getString(R.string.pixiedust_no_networks)
            }
        }

        viewModel.isScanning.observe(viewLifecycleOwner) { isScanning ->
            binding.buttonScanNetworks.isEnabled = !isScanning
            binding.progressBarScanning.visibility = if (isScanning) View.VISIBLE else View.GONE
        }

        viewModel.selectedNetwork.observe(viewLifecycleOwner) { network ->
            networkAdapter.setSelectedNetwork(network)
            binding.cardViewAttackControl.visibility = if (network != null) View.VISIBLE else View.GONE

            if (network != null) {
                binding.textViewSelectedNetwork.text = getString(
                    R.string.pixiedust_network_info,
                    network.ssid.ifBlank { getString(R.string.pixiedust_unknown_network) },
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

        val canAttack = hasRoot && hasBinaries && hasSelectedNetwork
        val isIdle = attackState is PixieAttackState.Idle

        binding.buttonStartAttack.isEnabled = canAttack && isIdle

        Log.d("PixieDustFragment", "System status - Root: $hasRoot, Binaries: $hasBinaries, Selected: $hasSelectedNetwork, CanAttack: $canAttack")
    }

    private fun updateAttackUI(state: PixieAttackState) {
        when (state) {
            is PixieAttackState.Idle -> {
                updateSystemStatus()
                binding.buttonStopAttack.isEnabled = false
                binding.progressBarAttack.visibility = View.GONE
                binding.cardViewResult.visibility = View.GONE
            }

            is PixieAttackState.Scanning,
            is PixieAttackState.CheckingRoot,
            is PixieAttackState.Preparing,
            is PixieAttackState.ExtractingData,
            is PixieAttackState.RunningAttack -> {
                binding.buttonStartAttack.isEnabled = false
                binding.buttonStopAttack.isEnabled = true
                binding.progressBarAttack.visibility = View.VISIBLE
                binding.cardViewResult.visibility = View.GONE
            }

            is PixieAttackState.Completed -> {
                updateSystemStatus()
                binding.buttonStopAttack.isEnabled = false
                binding.progressBarAttack.visibility = View.GONE
                binding.cardViewResult.visibility = View.VISIBLE

                val result = state.result
                if (result.success && result.pin != null) {
                    binding.textViewResult.text = getString(R.string.pixiedust_pin_found, result.pin)
                    binding.layoutResultActions.visibility = View.VISIBLE
                } else {
                    binding.textViewResult.text = getString(R.string.pixiedust_pin_not_found)
                    binding.layoutResultActions.visibility = View.GONE
                }
            }

            is PixieAttackState.Failed -> {
                updateSystemStatus()
                binding.buttonStopAttack.isEnabled = false
                binding.progressBarAttack.visibility = View.GONE
                binding.cardViewResult.visibility = View.VISIBLE
                binding.textViewResult.text = getString(R.string.pixiedust_attack_failed, state.error)
                binding.layoutResultActions.visibility = View.GONE
            }
        }
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