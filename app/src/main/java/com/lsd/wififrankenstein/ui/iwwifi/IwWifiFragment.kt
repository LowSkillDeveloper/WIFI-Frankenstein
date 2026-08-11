package com.lsd.wififrankenstein.ui.iwwifi

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentIwWifiBinding
import com.lsd.wififrankenstein.ui.iwwifi.models.IwInterface
import com.lsd.wififrankenstein.ui.iwwifi.models.IwLinkInfo
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.ui.settings.WlanInterfaceManagerViewModel
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.ChrootType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.NativeWifiHelper
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.launch

class IwWifiFragment : Fragment() {

    private var _binding: FragmentIwWifiBinding? = null
    private val binding get() = _binding!!

    private lateinit var iwWifiManager: IwWifiManager
    private lateinit var chrootManager: ChrootManager
    private lateinit var nativeWifiHelper: NativeWifiHelper
    private lateinit var wifiAdapter: IwWifiAdapter
    private lateinit var wlanInterfaceViewModel: WlanInterfaceManagerViewModel

    private var availableInterfaces: List<IwInterface> = emptyList()
    private var selectedInterface: String = "wlan0"
    private var isScanning = false
    private var isChrootMounted = false
    private var setupComplete = false
    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? =
        null

    companion object {
        private const val TAG = "IwWifiFragment"
        private const val HANDSHAKE_PREFS = "handshake_capture"
        private const val KEY_SCAN_IFACE = "scan_interface"
        private const val IW_PREFS = "iw_scanner_prefs"
        private const val KEY_USE_NATIVE = "use_native_iw"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIwWifiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        iwWifiManager = IwWifiManager(requireContext())
        chrootManager = ChrootManager.get(requireContext())
        nativeWifiHelper = NativeWifiHelper(requireContext())
        wlanInterfaceViewModel =
            ViewModelProvider(requireActivity()).get(WlanInterfaceManagerViewModel::class.java)

        setupModeToggle()
        checkChrootAndSetup()
    }

    private fun isNativeEnabled(): Boolean {
        return requireContext().getSharedPreferences(IW_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_NATIVE, false)
    }

    private fun setupModeToggle() {
        val prefs = requireContext().getSharedPreferences(IW_PREFS, Context.MODE_PRIVATE)
        val hasChroot = chrootManager.getChrootType() is ChrootType.Root
        val useNative = prefs.getBoolean(KEY_USE_NATIVE, false) && !hasChroot
        binding.toggleModeGroup.check(if (useNative) R.id.modeRoot else R.id.modeChroot)
        binding.toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val nativeSelected = checkedId == R.id.modeRoot
            prefs.edit().putBoolean(KEY_USE_NATIVE, nativeSelected).apply()
            checkChrootAndSetup()
        }
    }

    private fun updateModeToggleAvailability() {
        if (_binding == null) return
        val chrootAvailable = chrootManager.getChrootType() is ChrootType.Root
        binding.modeChroot.isEnabled = chrootAvailable
        binding.modeChroot.alpha = if (chrootAvailable) 1f else 0.45f
        if (!chrootAvailable && binding.modeChroot.isChecked) {
            binding.toggleModeGroup.check(R.id.modeRoot)
        }
    }

    private fun registerPrefsListener() {
        val prefs = requireContext().getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
        prefsListener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                if (_binding == null) return@OnSharedPreferenceChangeListener
                if (key == KEY_SCAN_IFACE) {
                    val newIface = sharedPreferences.getString(key, "wlan0") ?: "wlan0"
                    if (newIface != selectedInterface) {
                        selectedInterface = newIface
                        binding.autoCompleteScanInterface.setText(newIface, false)
                        loadLinkInfo()
                        Log.d(TAG, "Interface changed externally to: $selectedInterface")
                    }
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun setupViews() {
        setupRecyclerView()
        setupSwipeRefresh()
        setupButtons()
    }

    private fun setupRecyclerView() {
        wifiAdapter = IwWifiAdapter { network ->
            showNetworkDetails(network)
        }

        binding.recyclerViewNetworks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = wifiAdapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.scrollContent.setOnRefreshListener {
            if (!isScanning) {
                scanWifiNetworks()
            } else {
                binding.scrollContent.isRefreshing = false
            }
        }
    }

    private fun setupButtons() {
        binding.buttonScan.setOnClickListener {
            if (!isScanning) {
                scanWifiNetworks()
            }
        }

        binding.buttonRefresh.setOnClickListener {
            loadInterfaces()
        }
    }

    private fun checkChrootAndSetup() {
        showScanInterface()
        if (!setupComplete) {
            setupViews()
            registerPrefsListener()
            setupComplete = true
        }
        updateModeToggleAvailability()

        if (isNativeEnabled() || chrootManager.getChrootType() !is ChrootType.Root) {
            lifecycleScope.launch {
                nativeWifiHelper.ensureReady()
                loadInterfaces()
            }
        } else {
            wlanInterfaceViewModel.startPolling()
            lifecycleScope.launch { checkRootAndMount() }
        }
    }

    private fun showScanInterface() {
        binding.scrollContent.visibility = View.VISIBLE
    }

    private suspend fun checkRootAndMount() {
        try {
            Log.d(TAG, "Checking root access")
            val hasRoot = Shell.getShell().isRoot
            Log.d(TAG, "Root access: $hasRoot")

            if (!hasRoot) {
                showError(getString(R.string.iw_wifi_root_required))
                return
            }

            Log.i(TAG, "Root access confirmed, mounting chroot")
            mountAndLoad()

        } catch (e: Exception) {
            Log.e(TAG, "Error checking requirements", e)
            showError(getString(R.string.iw_wifi_requirements_error, e.message))
        }
    }

    private suspend fun mountAndLoad() {
        try {
            val mounted = chrootManager.mountChroot()
            isChrootMounted = mounted

            if (!mounted) {
                showError(getString(R.string.iw_wifi_chroot_mount_failed))
                return
            }

            Log.d(TAG, "Chroot mounted successfully, loading interfaces")
            loadInterfaces()

        } catch (e: Exception) {
            Log.e(TAG, "Error mounting chroot", e)
            showError(getString(R.string.iw_wifi_chroot_error, e.message))
        }
    }

    private fun unmountChroot() {
        if (isChrootMounted) {
            try {
                chrootManager.unmountChroot()
                isChrootMounted = false
                Log.d(TAG, "Chroot unmounted")
            } catch (e: Exception) {
                Log.e(TAG, "Error unmounting chroot", e)
            }
        }
    }

    private fun loadInterfaces() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Loading available interfaces")
                binding.progressBar.visibility = View.VISIBLE
                binding.textStatus.text = getString(R.string.iw_wifi_loading_interfaces)

                val ifaces = if (isNativeEnabled()) {
                    nativeWifiHelper.getAvailableInterfaces()
                } else {
                    iwWifiManager.getAvailableInterfaces()
                }
                availableInterfaces = ifaces
                val names = ifaces.map { it.name }.toTypedArray()
                Log.d(TAG, "Found ${names.size} interfaces")

                val prefs =
                    requireContext().getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
                val savedScan = prefs.getString(KEY_SCAN_IFACE, names.getOrNull(0) ?: "wlan0")!!

                val validScan = validateInterface(savedScan, names, "wlan0")
                setupDropdown(binding.autoCompleteScanInterface, names, validScan)

                selectedInterface = binding.autoCompleteScanInterface.text?.toString() ?: "wlan0"
                attachDropdownListeners(prefs)

                if (ifaces.isNotEmpty()) {
                    loadLinkInfo()
                } else {
                    showError(getString(R.string.iw_wifi_no_interfaces))
                }

            } catch (e: Exception) {
                if (_binding == null) return@launch
                Log.e(TAG, "Error loading interfaces", e)
                showError(getString(R.string.iw_wifi_interfaces_error, e.message))
                setupDropdown(binding.autoCompleteScanInterface, arrayOf("wlan0"), "wlan0")
                selectedInterface = "wlan0"
            } finally {
                if (_binding != null) {
                    binding.progressBar.visibility = View.GONE
                    binding.textStatus.text = getString(R.string.iw_wifi_ready)
                }
            }
        }
    }

    private fun validateInterface(
        savedIface: String,
        availableNames: Array<String>,
        default: String
    ): String {
        return if (availableNames.contains(savedIface)) savedIface else default
    }

    private fun setupDropdown(
        autoComplete: AutoCompleteTextView,
        names: Array<String>,
        savedName: String
    ) {
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        autoComplete.setAdapter(adapter)
        val idx = names.indexOfFirst { it == savedName }
        autoComplete.setText(
            names.getOrNull(idx.coerceAtLeast(0)) ?: names.firstOrNull() ?: "",
            false
        )
    }

    private fun attachDropdownListeners(prefs: android.content.SharedPreferences) {
        binding.autoCompleteScanInterface.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val iface =
                    (parent?.getItemAtPosition(position) as? String) ?: return@OnItemClickListener
                prefs.edit().putString(KEY_SCAN_IFACE, iface).apply()
                selectedInterface = iface
                Log.d(TAG, "Selected interface: $selectedInterface")
                Toast.makeText(
                    requireContext(),
                    getString(R.string.iw_wifi_interface_selected, selectedInterface),
                    Toast.LENGTH_SHORT
                ).show()
                loadLinkInfo()
            }
    }

    private fun loadLinkInfo() {
        lifecycleScope.launch {
            try {
                val linkInfo = if (isNativeEnabled()) {
                    nativeWifiHelper.getLinkInfo(selectedInterface)
                } else {
                    iwWifiManager.getLinkInfo(selectedInterface)
                }
                updateLinkInfoCard(linkInfo)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading link info", e)
            }
        }
    }

    private fun updateLinkInfoCard(linkInfo: IwLinkInfo) {
        binding.apply {
            if (linkInfo.connected) {
                cardCurrentConnection.visibility = View.VISIBLE
                textConnectedSsid.text = getString(R.string.iw_wifi_connected_ssid, linkInfo.ssid)
                textConnectedBssid.text =
                    getString(R.string.iw_wifi_connected_bssid, linkInfo.bssid)
                textConnectedFrequency.text =
                    getString(R.string.iw_wifi_connected_frequency, linkInfo.frequency)
                textConnectedTxBitrate.text =
                    getString(R.string.iw_wifi_connected_tx_bitrate, linkInfo.txBitrate)
                if (linkInfo.rxBitrate.isNotBlank()) {
                    textConnectedRxBitrate.text =
                        getString(R.string.iw_wifi_connected_rx_bitrate, linkInfo.rxBitrate)
                    textConnectedRxBitrate.visibility = View.VISIBLE
                } else {
                    textConnectedRxBitrate.visibility = View.GONE
                }
            } else {
                cardCurrentConnection.visibility = View.GONE
            }
        }
    }

    private fun scanWifiNetworks() {
        if (!isNativeEnabled()) {
            chrootManager.resetMountFailedCooldown()
        }
        if (isScanning) {
            Log.d(TAG, "Scan already in progress")
            return
        }

        lifecycleScope.launch {
            try {
                isScanning = true
                Log.d(TAG, "Starting WiFi scan on interface: $selectedInterface")

                binding.apply {
                    progressBar.visibility = View.VISIBLE
                    textStatus.text = getString(R.string.iw_wifi_scanning)
                    buttonScan.isEnabled = false
                }

                val networks = if (isNativeEnabled()) {
                    nativeWifiHelper.scanWifiNetworks(selectedInterface)
                } else {
                    iwWifiManager.scanWifiNetworks(selectedInterface)
                }
                Log.d(TAG, "Scan completed, found ${networks.size} networks")

                wifiAdapter.updateNetworks(networks)

                binding.apply {
                    textStatus.text = getString(R.string.iw_wifi_scan_complete, networks.size)

                    if (networks.isEmpty()) {
                        textEmptyState.visibility = View.VISIBLE
                        recyclerViewNetworks.visibility = View.GONE
                    } else {
                        textEmptyState.visibility = View.GONE
                        recyclerViewNetworks.visibility = View.VISIBLE
                    }
                }

            } catch (e: Exception) {
                if (_binding == null) return@launch
                Log.e(TAG, "Error scanning WiFi networks", e)
                showError(getString(R.string.iw_wifi_scan_error, e.message))
            } finally {
                isScanning = false
                if (_binding != null) {
                    binding.apply {
                        progressBar.visibility = View.GONE
                        buttonScan.isEnabled = true
                        scrollContent.isRefreshing = false
                    }
                }
            }
        }
    }

    private fun showNetworkDetails(network: IwWifiNetwork) {
        val detailsFragment = IwWifiDetailsFragment.newInstance(network, selectedInterface)
        detailsFragment.show(parentFragmentManager, "network_details")
    }

    private fun showError(message: String) {
        Log.e(TAG, "Showing error: $message")
        binding.textStatus.text = message
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView — unmounting chroot and cleaning up")
        prefsListener?.let { listener ->
            val prefs = requireContext().getSharedPreferences(
                HANDSHAKE_PREFS,
                Context.MODE_PRIVATE
            )
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
        prefsListener = null
        unmountChroot()
        _binding = null
    }
}