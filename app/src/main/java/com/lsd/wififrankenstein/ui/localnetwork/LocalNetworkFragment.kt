package com.lsd.wififrankenstein.ui.localnetwork

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.DialogDeviceDetailsBinding
import com.lsd.wififrankenstein.databinding.FragmentLocalNetworkBinding
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.launch

class LocalNetworkFragment : Fragment() {

    private var _binding: FragmentLocalNetworkBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LocalNetworkViewModel by activityViewModels()

    private var deviceAdapter: LocalDeviceAdapter? = null
    private var consoleAdapter: LocalNetworkConsoleAdapter? = null
    private var consoleVisible = false
    private var currentGateway = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocalNetworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSpinners()
        showScanInterface()
        setupRecyclerViews()
        setupButtons()
        observeViewModel()
    }

    private fun showScanInterface() {
        binding.scrollContent.visibility = View.VISIBLE
        binding.progressCard.visibility = View.VISIBLE
        binding.placeholderChroot.visibility = View.GONE
    }

    private fun setupSpinners() {
        val modeAdapter = ArrayAdapter.createFromResource(
            requireContext(), R.array.scan_modes, android.R.layout.simple_spinner_item
        )
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modeSpinner.adapter = modeAdapter
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val mode = if (pos == 0) ScanMode.NATIVE else ScanMode.CHROOT
                viewModel.setScanMode(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.modeSpinner.setSelection(0)

        viewModel.state.value?.let { state ->
            if (state.chrootAvailable) {
                binding.modeSpinner.setSelection(if (state.scanMode == ScanMode.CHROOT) 1 else 0)
            } else {
                binding.modeSpinner.setSelection(0)
            }
        }
    }

    private fun setupRecyclerViews() {
        deviceAdapter = viewModel.getAdapter(
            onDetails = { device -> showDeviceDetails(device) }
        )

        binding.recyclerViewDevices.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = deviceAdapter
        }

        consoleAdapter = LocalNetworkConsoleAdapter()
        binding.recyclerViewConsole.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = consoleAdapter
        }
    }

    private fun setupButtons() {
        binding.buttonScan.setOnClickListener {
            if (viewModel.state.value?.isScanning == true) {
                viewModel.cancelScan()
            } else {
                val iface = viewModel.state.value?.selectedInterface ?: "wlan0"
                viewModel.quickScan(iface)
            }
        }

        binding.buttonDetailedScan.setOnClickListener {
            if (viewModel.state.value?.isScanning == true) {
                viewModel.cancelScan()
            } else {
                val iface = viewModel.state.value?.selectedInterface ?: "wlan0"
                viewModel.detailedScan(iface)
            }
        }

        binding.buttonClearConsole.setOnClickListener {
            consoleAdapter?.clear()
        }

        binding.buttonClearDevices.setOnClickListener {
            viewModel.clearResults()
        }

        binding.buttonToggleConsole.setOnClickListener {
            consoleVisible = !consoleVisible
            binding.consoleContent.visibility = if (consoleVisible) View.VISIBLE else View.GONE
            binding.buttonToggleConsole.text = if (consoleVisible) {
                getString(R.string.router_scan_console_close)
            } else {
                getString(R.string.router_scan_console_open)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            updateUi(state)
        }
    }

    private fun updateUi(state: LocalNetworkState) {
        val isScanning = state.isScanning
        val deviceCount = deviceAdapter?.itemCount?.takeIf { it > 0 } ?: state.devices.size

        val bottomPadding = if (isScanning) {
            (88 * resources.displayMetrics.density).toInt()
        } else {
            0
        }
        binding.scrollContent.setPadding(
            binding.scrollContent.paddingLeft,
            binding.scrollContent.paddingTop,
            binding.scrollContent.paddingRight,
            bottomPadding
        )


        if (state.chrootAvailable && binding.modeSpinner.count > 0) {
            binding.modeSpinner.setSelection(if (state.scanMode == ScanMode.CHROOT) 1 else 0)
        }


        if (binding.interfaceSpinner.count == 0 && state.availableInterfaces.isNotEmpty()) {
            val ifaceAdapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item, state.availableInterfaces
            )
            ifaceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.interfaceSpinner.adapter = ifaceAdapter
            val selIdx = state.availableInterfaces.indexOf(state.selectedInterface)
            if (selIdx >= 0) binding.interfaceSpinner.setSelection(selIdx)
            binding.interfaceSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        pos: Int,
                        id: Long
                    ) {
                        viewModel.setInterface(state.availableInterfaces[pos])
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
        }

        binding.buttonScan.text = if (isScanning) getString(R.string.cancel) else getString(R.string.ln_quick_scan)
        binding.buttonDetailedScan.isEnabled = !isScanning

        if (state.subnet.isNotEmpty()) {
            binding.subnetInfoCard.visibility = View.VISIBLE
            binding.textSubnet.text = getString(R.string.ln_subnet, state.subnet)
            currentGateway = state.gateway
            binding.textGateway.text = getString(R.string.ln_gateway, state.gateway)
        }

        if (isScanning) {
            binding.animatedProgressBar.startAnimation()
            binding.progressCard.visibility = View.VISIBLE
            binding.textDeviceCount.text = getString(R.string.ln_devices, deviceCount)

            if (state.totalFound > 0) {
                binding.progressScan.isIndeterminate = false
                val max = state.totalFound
                val progress = state.scannedCount.coerceIn(0, max)
                binding.progressScan.max = max
                binding.progressScan.progress = progress
                binding.textProgressStatus.text = when (state.phase) {
                    "port_scan" -> getString(R.string.ln_port_scan_progress, progress, max)
                    else -> getString(R.string.ln_scan_progress_hosts, progress, max)
                }
            } else {
                binding.progressScan.isIndeterminate = true
                binding.textProgressStatus.text = when (state.phase) {
                    "port_scan" -> getString(R.string.ln_port_scanning)
                    "detect_subnet" -> getString(R.string.ln_detecting_subnet)
                    else -> getString(R.string.ln_scanning)
                }
            }
        } else {
            binding.animatedProgressBar.stopAnimation()
            binding.progressCard.visibility = View.GONE
            binding.progressScan.isIndeterminate = false
        }

        if (deviceCount > 0) {
            binding.textEmptyState.visibility = View.GONE
            binding.recyclerViewDevices.visibility = View.VISIBLE
            binding.textDeviceCount.text = getString(R.string.ln_devices, deviceCount)
        } else if (!isScanning) {
            binding.textEmptyState.visibility = View.VISIBLE
            binding.recyclerViewDevices.visibility = View.GONE
        }

        consoleAdapter?.addLines(state.consoleLines)

        state.error?.let { error ->
            if (!isScanning) {
                Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.retry)) { viewModel.quickScan("wlan0") }
                    .show()
            }
        }
    }

    private fun showDeviceDetails(device: LocalDevice) {
        val dialogBinding = DialogDeviceDetailsBinding.inflate(layoutInflater)

        dialogBinding.detailDeviceIcon.setImageResource(
            when (device.osType) {
                OSType.ANDROID -> R.drawable.ic_dev_android
                OSType.WINDOWS -> R.drawable.ic_dev_windows
                OSType.LINUX -> R.drawable.bg_console
                OSType.IOS -> R.drawable.ic_dev_apple
                OSType.MACOS -> R.drawable.ic_dev_computer
                OSType.PRINTER -> R.drawable.ic_dev_printer
                OSType.CAMERA -> R.drawable.ic_dev_camera
                OSType.ROUTER -> R.drawable.router_24px
                else -> R.drawable.computer_24px
            }
        )

        dialogBinding.detailIp.text = device.ip
        dialogBinding.detailMac.text = device.mac.ifEmpty { getString(R.string.not_available) }
        dialogBinding.detailVendor.text = device.vendor.ifEmpty { getString(R.string.unknown) }
        dialogBinding.detailHostname.text = device.hostname.ifEmpty { getString(R.string.unknown) }
        dialogBinding.detailNetbios.text = device.netbiosName.ifEmpty { getString(R.string.not_available) }
        dialogBinding.detailStatus.text =
            if (device.isAlive) getString(R.string.ln_online) else getString(R.string.ln_offline)
        dialogBinding.detailStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (device.isAlive) R.color.success_green else R.color.error_red
            )
        )
        dialogBinding.detailLatency.text =
            if (device.responseTimeMs > 0) "${device.responseTimeMs}ms" else getString(R.string.not_available)

        populateOsPorts(dialogBinding, device)

        val httpPort =
            device.openPorts.firstOrNull { it == 80 || it == 443 || it == 8080 || it == 8443 }
        if (httpPort != null) {
            dialogBinding.actionOpenBrowser.visibility = View.VISIBLE
            dialogBinding.actionOpenBrowser.setOnClickListener { openBrowser(device.ip, httpPort) }
        } else {
            dialogBinding.actionOpenBrowser.visibility = View.GONE
        }

        val hasScanData = device.openPorts.isNotEmpty() || device.os.isNotEmpty()
        dialogBinding.actionScanDevice.visibility = if (hasScanData) View.GONE else View.VISIBLE
        dialogBinding.actionScanDevice.setOnClickListener {
            dialogBinding.actionScanDevice.isEnabled = false
            dialogBinding.actionScanDevice.text = getString(R.string.ln_scanning)
            lifecycleScope.launch {
                val updated = viewModel.scanDevice(device.ip)
                if (updated != null && isAdded) {
                    populateOsPorts(dialogBinding, updated)
                    val httpPort =
                        updated.openPorts.firstOrNull { it == 80 || it == 443 || it == 8080 || it == 8443 }
                    if (httpPort != null) {
                        dialogBinding.actionOpenBrowser.visibility = View.VISIBLE
                        dialogBinding.actionOpenBrowser.setOnClickListener {
                            openBrowser(
                                updated.ip,
                                httpPort
                            )
                        }
                    }
                    dialogBinding.actionScanDevice.visibility = View.GONE
                } else if (isAdded) {
                    dialogBinding.actionScanDevice.text = getString(R.string.ln_scan_device)
                    dialogBinding.actionScanDevice.isEnabled = true
                }
            }
        }

        val canCut = viewModel.state.value?.let {
            it.hasRoot
        } ?: false
        dialogBinding.actionCutInternet.visibility = if (canCut) View.VISIBLE else View.GONE
        if (canCut) {
            dialogBinding.actionCutInternet.setOnClickListener { showCutNetworkDialog(device) }
        }

        val canRouterScan = viewModel.state.value?.routerScanAvailable ?: false
        dialogBinding.actionRouterScan.isEnabled = canRouterScan
        dialogBinding.actionRouterScan.alpha = if (canRouterScan) 1f else 0.4f
        dialogBinding.actionRouterScan.setOnClickListener { navigateToRouterScan(device.ip) }
        dialogBinding.actionWol.setOnClickListener {
            viewModel.sendWakeOnLan(
                requireContext(),
                device
            )
        }
        dialogBinding.actionWol.visibility =
            if (device.mac.isNotEmpty()) View.VISIBLE else View.GONE

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.ln_device_title, device.ip))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun populateOsPorts(binding: DialogDeviceDetailsBinding, device: LocalDevice) {
        if (device.os.isNotEmpty() || device.osFamily.isNotEmpty() || device.deviceType.isNotEmpty()) {
            binding.osCard.visibility = View.VISIBLE
            binding.detailOs.text = device.os.ifEmpty { getString(R.string.not_available) }

            if (device.osFamily.isNotEmpty()) {
                binding.osFamilyRow.visibility = View.VISIBLE
                binding.detailOsFamily.text = device.osFamily
            } else {
                binding.osFamilyRow.visibility = View.GONE
            }

            if (device.deviceType.isNotEmpty()) {
                binding.deviceTypeRow.visibility = View.VISIBLE
                binding.detailDeviceType.text = device.deviceType
            } else {
                binding.deviceTypeRow.visibility = View.GONE
            }

            if (device.osCpe.isNotEmpty()) {
                binding.osCpeRow.visibility = View.VISIBLE
                binding.detailOsCpe.text = device.osCpe
            } else {
                binding.osCpeRow.visibility = View.GONE
            }

            if (device.networkDistance.isNotEmpty()) {
                binding.networkDistanceRow.visibility = View.VISIBLE
                binding.detailNetworkDistance.text = device.networkDistance
            } else {
                binding.networkDistanceRow.visibility = View.GONE
            }
        } else {
            binding.osCard.visibility = View.GONE
        }
        if (device.openPorts.isNotEmpty()) {
            binding.portsCard.visibility = View.VISIBLE
            binding.detailPorts.text = device.openPorts.joinToString("\n") { port ->
                val service = getCommonServiceName(port)
                if (service.isNotEmpty()) {
                    getString(R.string.ln_port_tcp_service, port, service)
                } else {
                    getString(R.string.ln_port_tcp, port)
                }
            }
        } else {
            binding.portsCard.visibility = View.GONE
        }
    }

    private fun showCutNetworkDialog(device: LocalDevice) {
        val inputLayout = layoutInflater.inflate(R.layout.dialog_cut_network, null) as ViewGroup
        val editText = inputLayout.findViewById<EditText>(android.R.id.edit) ?: run {
            val et = EditText(requireContext()).apply {
                id = android.R.id.edit
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = "30"
                textSize = 16f
            }
            val lp = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 8, 0, 0)
            et.layoutParams = lp
            inputLayout.addView(et)
            et
        }
        editText.setText("30")

        val switchManual = MaterialSwitch(requireContext()).apply {
            text = getString(R.string.cut_manual_label)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 8, 0, 0)
        }

        switchManual.setOnCheckedChangeListener { _, isChecked ->
            editText.isEnabled = !isChecked
            editText.alpha = if (isChecked) 0.4f else 1.0f
        }

        inputLayout.addView(switchManual)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.ln_disconnect_internet))
            .setMessage(getString(R.string.ln_cut_target, device.ip, currentGateway))
            .setView(inputLayout)
            .setPositiveButton(getString(R.string.ln_cut)) { _, _ ->
                if (switchManual.isChecked) {
                    viewModel.cutInternet(device, currentGateway)
                } else {
                    val secs = editText.text.toString().toIntOrNull()?.coerceIn(5, 3600) ?: 30
                    viewModel.cutInternet(device, currentGateway, secs)
                }
            }
            .setNeutralButton(getString(R.string.ln_restore)) { _, _ ->
                viewModel.restoreInternet(device, currentGateway)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun openBrowser(ip: String, port: Int) {
        try {
            val scheme = if (port == 443 || port == 8443) "https" else "http"
            val url = "$scheme://$ip:$port"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open browser for $ip:$port", e)
            Snackbar.make(binding.root, getString(R.string.ln_cannot_open_browser), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun navigateToRouterScan(ip: String) {
        try {
            val bundle = Bundle().apply {
                putString("target_ip", ip)
            }
            findNavController().navigate(R.id.nav_router_scan, bundle)
        } catch (e: Exception) {
            Log.e(TAG, "Navigation to router scan failed", e)
            Snackbar.make(
                binding.root,
                getString(R.string.ln_router_scan_unavailable),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun getCommonServiceName(port: Int): String {
        return when (port) {
            20 -> "FTP-data"
            21 -> "FTP"
            22 -> "SSH"
            23 -> "Telnet"
            25 -> "SMTP"
            53 -> "DNS"
            80 -> "HTTP"
            110 -> "POP3"
            143 -> "IMAP"
            443 -> "HTTPS"
            445 -> "SMB"
            465 -> "SMTPS"
            587 -> "SMTP"
            993 -> "IMAPS"
            995 -> "POP3S"
            1433 -> "MSSQL"
            1521 -> "Oracle"
            1701 -> "L2TP"
            1723 -> "PPTP"
            1883 -> "MQTT"
            3306 -> "MySQL"
            3389 -> "RDP"
            5432 -> "PostgreSQL"
            5900 -> "VNC"
            5901 -> "VNC-1"
            6379 -> "Redis"
            8080 -> "HTTP-Proxy"
            8443 -> "HTTPS-Alt"
            9000 -> "Portainer"
            27017 -> "MongoDB"
            32400 -> "Plex"
            else -> ""
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "LocalNetworkFragment"
    }
}
