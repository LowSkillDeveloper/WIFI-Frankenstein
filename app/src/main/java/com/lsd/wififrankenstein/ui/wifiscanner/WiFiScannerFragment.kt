package com.lsd.wififrankenstein.ui.wifiscanner

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.ContextMenu
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WpsGeneratorActivity
import com.lsd.wififrankenstein.databinding.FragmentWifiScannerBinding
import com.lsd.wififrankenstein.service.ForegroundAttackService
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiDetailsFragment
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.ui.iwwifi.models.IwInterface
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.ui.notification.NotificationMessage
import com.lsd.wififrankenstein.ui.notification.NotificationService
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel
import com.lsd.wififrankenstein.ui.settings.WlanInterfaceManagerViewModel
import com.lsd.wififrankenstein.ui.updates.UpdateChecker
import com.lsd.wififrankenstein.ui.wpagenerator.WpaAlgorithmsHelper
import com.lsd.wififrankenstein.ui.wpsgenerator.WPSPin
import com.lsd.wififrankenstein.util.BottomSheetMenu
import com.lsd.wififrankenstein.util.BottomSheetMenuItem
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.ChrootType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.MacAddressUtils
import com.lsd.wififrankenstein.util.PixiePinProvider
import com.lsd.wififrankenstein.util.PskBruteForceEngines
import com.lsd.wififrankenstein.util.QrNavigationHelper
import com.lsd.wififrankenstein.util.RootlessManager
import com.lsd.wififrankenstein.util.SystemWpsConnector
import com.lsd.wififrankenstein.util.VendorChecker
import com.lsd.wififrankenstein.util.WiFiConnectionHelper
import com.lsd.wififrankenstein.util.WpsChrootConnectRunner
import com.lsd.wififrankenstein.util.WpsMethodSelector
import com.lsd.wififrankenstein.util.WpsPinGenerator
import com.lsd.wififrankenstein.util.WpsRootConnectHelper
import com.lsd.wififrankenstein.util.calculateDistanceString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale


class WiFiScannerFragment : Fragment() {

    @Suppress("DEPRECATION")
    private fun createScanResult(
        ssid: String,
        bssid: String,
        capabilities: String,
        level: Int,
        frequency: Int
    ): ScanResult {
        return try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
            val result = allocateInstance.invoke(unsafe, ScanResult::class.java) as ScanResult
            result.SSID = ssid
            result.BSSID = bssid
            result.capabilities = capabilities
            result.level = level
            result.frequency = frequency
            result
        } catch (e: Exception) {
            Log.w("WiFiScannerFragment", "Unsafe ScanResult creation failed, using fallback", e)
            ScanResult().apply {
                SSID = ssid
                BSSID = bssid
                this.capabilities = capabilities
                this.level = level
                this.frequency = frequency
            }
        }
    }

    private fun buildIwCapabilities(network: IwWifiNetwork): String {
        if (network.securityType.equals("Unknown", ignoreCase = true)) {
            return network.capabilities.ifBlank { "[ESS]" }
        }
        val pairwise = network.pairwiseCipher
            .replace(" ", "-")
            .uppercase(Locale.getDefault())
            .ifEmpty { "CCMP" }

        val hasSae = network.securityType.contains("WPA3", ignoreCase = true) ||
                network.authSuite.contains("SAE", ignoreCase = true)
        val hasPsk = network.authSuite.contains("PSK", ignoreCase = true) ||
                network.securityType.contains("WPA2", ignoreCase = true) ||
                network.securityType.contains("WPA", ignoreCase = true)

        val tokens = mutableListOf<String>()
        if (hasPsk) tokens.add("[WPA2-PSK-$pairwise]")
        if (hasSae) tokens.add("[WPA3-SAE-$pairwise]")
        if (tokens.isEmpty()) {
            when {
                network.securityType.contains(
                    "WPA",
                    ignoreCase = true
                ) -> tokens.add("[WPA-PSK-$pairwise]")

                network.securityType.contains("WEP", ignoreCase = true) -> tokens.add("[WEP]")
            }
        }
        val wpsCaps = if (network.wpsEnabled) "[WPS]" else ""
        return "${tokens.joinToString("")}$wpsCaps[ESS]"
    }

    private var _binding: FragmentWifiScannerBinding? = null
    private val binding get() = _binding!!

    private lateinit var notificationService: NotificationService

    private var wpaAlgorithmsHelper: WpaAlgorithmsHelper? = null
    private lateinit var wpsPinGenerator: WpsPinGenerator

    private lateinit var wifiAdapter: WifiAdapter
    private lateinit var iwWifiAdapter: IwWifiScannerAdapter
    private val dbSetupViewModel: DbSetupViewModel by lazy {
        DbSetupViewModel.getInstance(requireActivity().application)
    }
    private val viewModel: WiFiScannerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WiFiScannerViewModel(requireActivity().application) as T
            }
        }
    }


    private val settingsViewModel: SettingsViewModel by viewModels()

    private lateinit var iwWifiManager: IwWifiManager
    private lateinit var wlanInterfaceViewModel: WlanInterfaceManagerViewModel
    private var scanInterface = "wlan0"
    private var availableInterfaces: List<IwInterface> = emptyList()
    private var hasMultipleInterfaces = false
    private var hasRoot = false
    private var hasChroot = false
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var modePollingJob: Job? = null
    private var isIwScanSelected = false
    private var isUpdatingScanMode = false

    private var selectedWifi: ScanResult? = null
    private var selectedIwNetwork: IwWifiNetwork? = null
    private var correctionFactor = 1.0
    private var hasScanned = false
    private var isSearchByMac = true

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
                binding.buttonScanWifi.isEnabled = true
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWifiScannerBinding.inflate(inflater, container, false)

        initUI()
        observeViewModel()

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            dbSetupViewModel.loadDbList()
        }

        notificationService = NotificationService(requireContext())
        checkForNotifications()

        iwWifiManager = IwWifiManager(requireContext())
        wlanInterfaceViewModel =
            ViewModelProvider(requireActivity()).get(WlanInterfaceManagerViewModel::class.java)

        viewLifecycleOwner.lifecycleScope.launch {
            hasRoot = withContext(Dispatchers.IO) {
                com.lsd.wififrankenstein.WifiApplication.checkRootAccess()
            }
            if (hasRoot) {
                hasChroot = withContext(Dispatchers.IO) {
                    try {
                        val cm = ChrootManager.get(requireContext())
                        val chrootType = cm.getChrootType()
                        chrootType is ChrootType.Root
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            if (!isAdded || view == null) return@launch
            updateScanModeToggleVisibility()
            loadInterfaces()
            setupInterfaceSpinner()
            setupMonitorModeBanner()
            wlanInterfaceViewModel.startPolling()
            startModePolling()

            if (shouldScanOnStartup() && !hasScanned) {
                startWifiScan()
            }
        }

        wlanInterfaceViewModel.interfaceStatuses.observe(viewLifecycleOwner) { statuses ->
            val currentNames = statuses.map { it.name }.toSet()


            if (scanInterface !in currentNames) {
                val fallback = currentNames.firstOrNull()
                if (fallback != null && fallback != scanInterface) {
                    val previous = scanInterface
                    scanInterface = fallback
                    iwWifiManager.saveSelectedInterface(scanInterface)
                    syncIwModeForNonWlan0()
                    updateAdapterForInterface()
                    Log.w(
                        "WiFiScannerFragment",
                        "Scan interface '$previous' no longer available, switched to '$scanInterface'"
                    )
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.ws_scan_interface_changed, previous, scanInterface),
                        Toast.LENGTH_SHORT
                    ).show()

                    if (hasMultipleInterfaces) {
                        val adapter = binding.spinnerInterface.adapter as? ArrayAdapter<String>
                        val displayNames = adapter?.let {
                            (0 until it.count).map { i -> it.getItem(i) ?: "" }
                        } ?: emptyList()
                        val idx = displayNames.indexOfFirst { it.startsWith(scanInterface) }
                        if (idx >= 0) binding.spinnerInterface.setSelection(idx, false)
                    }
                }
            }

            val hasMultipleNow = currentNames.size > 1
            if (hasMultipleNow != hasMultipleInterfaces) {
                viewLifecycleOwner.lifecycleScope.launch {
                    loadInterfaces()
                    setupInterfaceSpinner()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                wpaAlgorithmsHelper = WpaAlgorithmsHelper(requireContext())
            } catch (e: OutOfMemoryError) {
                Log.e("WiFiScannerFragment", "OutOfMemoryError initializing WpaAlgorithmsHelper", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_insufficient_memory),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        wpsPinGenerator = WpsPinGenerator()

        binding.searchTypeToggle.apply {
            check(R.id.button_search_mac)
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    viewModel.setSearchType(checkedId == R.id.button_search_mac)
                }
            }
        }

        if (shouldCheckUpdates()) {
            setupUpdateBanner()
        }
    }

    private suspend fun generateWpaAlgorithms(): Map<String, List<NetworkDatabaseResult>> {
        val helper = wpaAlgorithmsHelper ?: return emptyMap()
        val networks = getActiveScanResults()
        val allResults = mutableMapOf<String, MutableList<NetworkDatabaseResult>>()

        networks.forEach { network ->
            val results = withContext(Dispatchers.IO) {
                helper.generateKeys(network.SSID, network.BSSID) ?: emptyList()
            }

            if (results.isNotEmpty()) {
                val networkResults = mutableListOf<NetworkDatabaseResult>()

                results.filter { it.supportState == 2 }.forEach { wpaResult ->
                    wpaResult.keys.forEachIndexed { index, key ->
                        val keyDisplayName = if (wpaResult.keys.size > 1) {
                            "${wpaResult.algorithm} (Key ${index + 1}/${wpaResult.keys.size})"
                        } else {
                            wpaResult.algorithm
                        }

                        val singleKeyResult =
                            com.lsd.wififrankenstein.ui.wpagenerator.WpaResult(
                                keys = listOf(key),
                                algorithm = keyDisplayName,
                                generationTime = wpaResult.generationTime,
                                supportState = wpaResult.supportState
                            )

                        networkResults.add(
                            NetworkDatabaseResult(
                                network = network,
                                databaseInfo = emptyMap(),
                                databaseName = keyDisplayName,
                                resultType = ResultType.WPA_ALGORITHM,
                                wpaResult = singleKeyResult
                            )
                        )
                    }
                }

                if (networkResults.isNotEmpty()) {
                    allResults[network.BSSID?.lowercase(Locale.ROOT) ?: ""] = networkResults
                }
            }
        }
        return allResults
    }

    private suspend fun generateWpsAlgorithms(): Map<String, List<NetworkDatabaseResult>> {
        val networks = getActiveScanResults()
        val allResults = mutableMapOf<String, MutableList<NetworkDatabaseResult>>()

        networks.forEach { network ->
            val pins = withContext(Dispatchers.IO) {
                generateWpsPinsForNetwork(requireContext(), network)
            }

            val filteredPins = pins.filter { pin ->
                pin.sugg || shouldShowQuestionMark(pin)
            }

            if (filteredPins.isNotEmpty()) {
                val networkResults = mutableListOf<NetworkDatabaseResult>()

                val sortedPins = sortPinsByPriority(filteredPins)

                sortedPins.forEach { wpsPin ->
                    networkResults.add(
                        NetworkDatabaseResult(
                            network = network,
                            databaseInfo = emptyMap(),
                            databaseName = wpsPin.name,
                            resultType = ResultType.WPS_ALGORITHM,
                            wpsPin = wpsPin
                        )
                    )
                }

                allResults[network.BSSID?.lowercase(Locale.ROOT) ?: ""] = networkResults
            }
        }
        return allResults
    }

    private fun sortPinsByPriority(pins: List<WPSPin>): List<WPSPin> {
        return pins.sortedWith(compareBy<WPSPin> { pin ->
            when {
                pin.sugg && !pin.isFrom3WiFi -> 0
                pin.sugg && pin.isFrom3WiFi && pin.additionalData["exact_match"] == true -> 1
                pin.sugg && pin.isFrom3WiFi -> 2
                !pin.sugg && pin.isFrom3WiFi -> 3
                !pin.sugg && !pin.isFrom3WiFi && pin.additionalData["source"] == "inapp_database" -> 4
                !pin.sugg && !pin.isFrom3WiFi && !pin.isExperimental -> 5
                !pin.sugg && !pin.isFrom3WiFi && pin.isExperimental -> 6
                else -> 7
            }
        }.thenByDescending { it.score })
    }

    private fun shouldShowQuestionMark(pin: WPSPin): Boolean {
        val source = pin.additionalData["source"] as? String
        val exactMatch = pin.additionalData["exact_match"] as? Boolean ?: false

        return when {
            pin.isFrom3WiFi && !exactMatch -> true
            source == "inapp_database" -> true
            source == "neighbor_search" && !pin.sugg -> true
            else -> false
        }
    }

    private suspend fun generateWpsPinsForNetwork(
        context: Context,
        network: ScanResult
    ): List<WPSPin> = withContext(Dispatchers.IO) {
        val pins = mutableListOf<WPSPin>()

        val suggestedPins =
            wpsPinGenerator.generateSuggestedPins(network.BSSID, includeExperimental = true)
        val allPins = wpsPinGenerator.generateAllPins(network.BSSID, includeExperimental = true)

        suggestedPins.forEach { pinResult ->
            pins.add(
                WPSPin(
                    mode = 0,
                    name = pinResult.algorithm,
                    pin = pinResult.pin,
                    sugg = true,
                    score = 1.0,
                    additionalData = mapOf("mode" to pinResult.mode),
                    isFrom3WiFi = false,
                    isExperimental = pinResult.isExperimental
                )
            )
        }

        val nonSuggestedPins = allPins.filter { allPin ->
            suggestedPins.none { suggestedPin ->
                suggestedPin.pin == allPin.pin && suggestedPin.algorithm == allPin.algorithm
            }
        }

        nonSuggestedPins.forEach { pinResult ->
            pins.add(
                WPSPin(
                    mode = 0,
                    name = pinResult.algorithm,
                    pin = pinResult.pin,
                    sugg = false,
                    score = 0.0,
                    additionalData = mapOf("mode" to pinResult.mode),
                    isFrom3WiFi = false,
                    isExperimental = pinResult.isExperimental
                )
            )
        }

        val dbPins = searchWpsPinsInDatabases(context, network.BSSID)
        pins.addAll(dbPins)

        pins.distinctBy { it.pin }.sortedWith(compareBy<WPSPin> { pin ->
            when {
                pin.sugg && !pin.isFrom3WiFi -> 0
                pin.sugg && pin.isFrom3WiFi -> 1
                !pin.sugg && pin.isFrom3WiFi -> 2
                !pin.sugg && !pin.isFrom3WiFi && !pin.isExperimental -> 3
                else -> 4
            }
        }.thenByDescending { it.score })
    }

    private suspend fun searchWpsPinsInDatabases(context: Context, bssid: String): List<WPSPin> =
        withContext(Dispatchers.IO) {
            val pins = mutableListOf<WPSPin>()

            try {
                val localHelper = LocalAppDbHelper(context)
                val searchFormats = MacAddressUtils.generateAllFormats(bssid)

                searchFormats.forEach { format ->
                    val results = localHelper.searchRecordsWithFilters(
                        query = format,
                        filterByName = false,
                        filterByMac = true,
                        filterByPassword = false,
                        filterByWps = true
                    )

                    results.forEach { network ->
                        if (!network.wpsCode.isNullOrEmpty() && isValidWpsPin(network.wpsCode)) {
                            pins.add(
                                WPSPin(
                                    mode = 0,
                                    name = context.getString(R.string.source_local_database),
                                    pin = network.wpsCode,
                                    sugg = true,
                                    score = 1.0,
                                    isFrom3WiFi = true,
                                    additionalData = mapOf(
                                        "source" to "local_database",
                                        "exact_match" to (format.equals(bssid, ignoreCase = true))
                                    )
                                )
                            )
                        }
                    }
                }

                val dbItems = dbSetupViewModel.dbList.value?.filter {
                    it.dbType == DbType.SQLITE_FILE_P3WIFI || it.dbType == DbType.SQLITE_FILE_CUSTOM ||
                            it.dbType == DbType.SMARTLINK_SQLITE_FILE_P3WIFI || it.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM
                } ?: emptyList()

                PixiePinProvider.find3WiFiPins(context, bssid, dbItems).forEach { scored ->
                    pins.add(
                        WPSPin(
                            mode = 0,
                            name = context.getString(R.string.from_database),
                            pin = scored.pin,
                            sugg = true,
                            score = 1.0,
                            isFrom3WiFi = true,
                            additionalData = mapOf(
                                "source" to scored.source,
                                "exact_match" to true
                            )
                        )
                    )
                }

                PixiePinProvider.findCustomPins(context, bssid, dbItems).forEach { scored ->
                    pins.add(
                        WPSPin(
                            mode = 0,
                            name = context.getString(R.string.source_custom_database),
                            pin = scored.pin,
                            sugg = true,
                            score = 1.0,
                            isFrom3WiFi = true,
                            additionalData = mapOf(
                                "source" to scored.source,
                                "exact_match" to true
                            )
                        )
                    )
                }

                PixiePinProvider.find3WiFiNeighborPins(context, bssid, dbItems).forEach { scored ->
                    pins.add(
                        WPSPin(
                            mode = 0,
                            name = context.getString(R.string.medium_neighbor),
                            pin = scored.pin,
                            sugg = scored.score >= 80,
                            score = scored.score / 100.0,
                            isFrom3WiFi = true,
                            additionalData = mapOf(
                                "source" to "neighbor_search"
                            )
                        )
                    )
                }

            } catch (e: Exception) {
                Log.e(
                    "WiFiScannerFragment",
                    "Error searching WPS pins in databases",
                    e
                )
            }

            pins.distinctBy { it.pin }
        }

    private fun isValidWpsPin(pin: String): Boolean {
        return pin.matches(WPS_PIN_REGEX)
    }

    private fun shouldCheckUpdates(): Boolean {
        return requireActivity().getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("check_updates_on_open", true)
    }

    private fun shouldScanOnStartup(): Boolean {
        val prefs = requireActivity().getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("scan_on_startup", true)
    }

    private fun initUI() {
        wifiAdapter = WifiAdapter(emptyList(), requireContext(), viewModel.prefs)
        iwWifiAdapter = IwWifiScannerAdapter(emptyList(), requireContext(), viewModel.prefs)

        wifiAdapter.setOnScrollToTopListener {
            if (_binding != null) {
                binding.recyclerViewWifi.post {
                    binding.recyclerViewWifi.postDelayed({
                        if (_binding != null) {
                            binding.recyclerViewWifi.scrollToPosition(0)
                        }
                    }, 300)
                }
            }
        }

        iwWifiAdapter.setOnScrollToTopListener {
            if (_binding != null) {
                binding.recyclerViewWifi.post {
                    binding.recyclerViewWifi.postDelayed({
                        if (_binding != null) {
                            binding.recyclerViewWifi.scrollToPosition(0)
                        }
                    }, 300)
                }
            }
        }

        binding.recyclerViewWifi.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = wifiAdapter
        }

        wifiAdapter.setOnItemClickListener { view, scanResult ->
            selectedWifi = scanResult
            selectedIwNetwork = null
            showCustomContextMenu()
        }

        iwWifiAdapter.setOnItemClickListener { view, iwNetwork ->
            selectedIwNetwork = iwNetwork
            selectedWifi = createScanResult(
                ssid = iwNetwork.ssid,
                bssid = iwNetwork.bssid,
                capabilities = buildIwCapabilities(iwNetwork),
                level = iwNetwork.signalStrength,
                frequency = iwNetwork.frequency.toIntOrNull() ?: 2412
            )
            showCustomContextMenu()
        }

        updateAdapterForInterface()

        binding.scanModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || _binding == null || isUpdatingScanMode) return@addOnButtonCheckedListener
            val iwSelected = checkedId == R.id.button_scan_iw
            if (isIwScanSelected != iwSelected) {
                isIwScanSelected = iwSelected
                if (!iwSelected) {
                    switchToStandardInterface()
                }
                updateAdapterForInterface()
            }
            startWifiScan()
        }

        binding.buttonScanWifi.setOnClickListener {
            binding.buttonDbCheck.visibility = View.VISIBLE
            startWifiScan()
        }

        binding.buttonAlgoGen.setOnClickListener {
            if (viewModel.isChecking.value != true) {
                showProgressBar()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val wpsResults = generateWpsAlgorithms()
                        val wpaResults = generateWpaAlgorithms()
                        val combined = mutableMapOf<String, MutableList<NetworkDatabaseResult>>()
                        for ((bssid, entries) in wpsResults) {
                            combined[bssid] = entries.toMutableList()
                        }
                        for ((bssid, entries) in wpaResults) {
                            combined.getOrPut(bssid) { mutableListOf() }.addAll(entries)
                        }
                        withContext(Dispatchers.Main) {
                            if (_binding == null) return@withContext
                            mergeResultsToBothAdapters(combined)
                            hideProgressBar()
                            val totalNetworks = combined.size
                            val message = if (totalNetworks > 0) {
                                getString(R.string.wpa_algorithms_generated, totalNetworks)
                            } else {
                                getString(R.string.ws_no_algorithms_found)
                            }
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("WiFiScannerFragment", "Error generating algorithms", e)
                        withContext(Dispatchers.Main) {
                            if (_binding == null) return@withContext
                            hideProgressBar()
                            Toast.makeText(requireContext(), getString(R.string.ws_error), Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            } else {
                Toast.makeText(
                    context,
                    getString(R.string.database_check_in_progress),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.buttonDbCheck.setOnClickListener {
            if (viewModel.isChecking.value != true) {
                viewLifecycleOwner.lifecycleScope.launch {
                    dbSetupViewModel.loadDbList()
                    val checkWpaSec = requireContext()
                        .getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getBoolean("check_wpasec", true)
                    val databases = dbSetupViewModel.dbList.value?.filter {
                        it.dbType == DbType.WIFI_API ||
                                it.dbType == DbType.SQLITE_FILE_P3WIFI ||
                                it.dbType == DbType.SQLITE_FILE_CUSTOM ||
                                it.dbType == DbType.LOCAL_APP_DB ||
                                it.dbType == DbType.SMARTLINK_SQLITE_FILE_P3WIFI ||
                                it.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM
                    } ?: emptyList()

                    if (databases.isEmpty() && !checkWpaSec) {
                        Toast.makeText(
                            context,
                            getString(R.string.no_databases_configured),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }

                    showProgressBar()
                    viewModel.checkNetworksInDatabases(
                        getActiveScanResults(), databases, checkWpaSec
                    )
                }
            } else {
                Toast.makeText(
                    context,
                    getString(R.string.database_check_in_progress),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.swipeRefreshLayout.setOnRefreshListener { startWifiScan() }
    }

    private fun observeViewModel() {
        viewModel.wifiList.observe(viewLifecycleOwner) { wifiList ->
            if (_binding == null) return@observe
            wifiAdapter.updateData(wifiList)
            binding.swipeRefreshLayout.isRefreshing = false
            binding.buttonScanWifi.isEnabled = true
        }

        viewModel.iwWifiList.observe(viewLifecycleOwner) { iwList ->
            if (_binding == null) return@observe
            iwWifiAdapter.updateData(iwList)
            binding.swipeRefreshLayout.isRefreshing = false
            binding.buttonScanWifi.isEnabled = true
        }

        viewModel.searchByMac.observe(viewLifecycleOwner) { searchByMac ->
            if (_binding == null) return@observe
            isSearchByMac = searchByMac
            val currentCheckedId = binding.searchTypeToggle.checkedButtonId
            val expectedId = if (searchByMac) R.id.button_search_mac else R.id.button_search_ssid

            if (currentCheckedId != expectedId) {
                binding.searchTypeToggle.check(expectedId)
            }
        }

        viewModel.wifiEnabled.observe(viewLifecycleOwner) { isEnabled ->
            if (!isEnabled) {
                showWifiDisabledDialog()
            }
        }

        viewModel.locationEnabled.observe(viewLifecycleOwner) { isEnabled ->
            if (!isEnabled) {
                showLocationDisabledDialog()
            }
        }

        viewModel.scanState.observe(viewLifecycleOwner) { message ->
            if (_binding == null) return@observe
            binding.swipeRefreshLayout.isRefreshing = false
            binding.buttonScanWifi.isEnabled = true

            when (message) {
                getString(R.string.no_networks_found_nearby),
                getString(R.string.scanning_failed_generic) -> {
                    showToastWithDuration(message, 1000)
                }

                getString(R.string.scanning_completed) -> {
                    showToastWithDuration(message, 700)
                }

                getString(R.string.scanning_wifi) -> {
                    showToastWithDuration(message, 700)
                }

                else -> {
                    showToastWithDuration(message, 1000)
                }
            }
        }

        viewModel.databaseResults.observe(viewLifecycleOwner) { results ->
            if (_binding == null) return@observe
            wifiAdapter.mergeDatabaseResults(results)
            iwWifiAdapter.mergeDatabaseResults(results)
            hideProgressBar()
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (_binding == null) return@observe
            if (error.isNullOrBlank()) return@observe
            hideProgressBar()
            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
        }

        viewModel.isChecking.observe(viewLifecycleOwner) { isChecking ->
            if (isChecking) {
                showProgressBar()
            } else {
                hideProgressBar()
            }
        }

        dbSetupViewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading == true) {
                showProgressBar()
            } else {
                hideProgressBar()
            }
        }
    }

    private fun showWifiDisabledDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.wifi_disabled))
            .setMessage(getString(R.string.wifi_disabled))
            .setPositiveButton(getString(R.string.turn_on_wifi)) { _, _ ->
                if (hasRoot) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val enabled = try {
                            ChrootManager.get(requireContext()).svcWifiToggle(true)
                        } catch (e: Exception) {
                            Log.e("WiFiScannerFragment", "Failed to enable WiFi via root", e)
                            false
                        }
                        val wifiManager = requireContext().applicationContext
                            .getSystemService(Context.WIFI_SERVICE) as WifiManager
                        var ready = false
                        if (enabled) {
                            repeat(30) {
                                if (wifiManager.isWifiEnabled) {
                                    ready = true
                                    return@repeat
                                }
                                delay(500)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            if (ready) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.wifi_enabled_via_root),
                                    Toast.LENGTH_SHORT
                                ).show()
                                startWifiScan()
                            } else {
                                openWifiSettings()
                            }
                        }
                    }
                } else {
                    openWifiSettings()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun openWifiSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.error_general, e.message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showLocationDisabledDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.location_services_disabled))
            .setMessage(getString(R.string.location_services_disabled))
            .setPositiveButton(getString(R.string.turn_on_location)) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_general, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun checkForNotifications() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val notification = notificationService.checkForNotifications(NOTIFICATION_URL)
                notification?.let {
                    showNotificationDialog(it)
                }
            } catch (e: Exception) {
                Log.e("WiFiScannerFragment", "Error checking notifications", e)
            }
        }
    }

    private fun showNotificationDialog(notification: NotificationMessage) {
        val language = notificationService.getCurrentLanguageCode()

        val dialogView = layoutInflater.inflate(R.layout.dialog_app_notification, null)

        val imageView = dialogView.findViewById<ImageView>(R.id.notificationImage)
        val messageView = dialogView.findViewById<TextView>(R.id.notificationMessage)
        val linkView = dialogView.findViewById<TextView>(R.id.notificationLink)
        val primaryButton = dialogView.findViewById<Button>(R.id.primaryButton)
        val secondaryButton = dialogView.findViewById<Button>(R.id.secondaryButton)

        messageView.text = notification.getLocalizedMessage(language)
        messageView.movementMethod = LinkMovementMethod.getInstance()

        if (notification.imageUrl != null) {
            imageView.visibility = View.VISIBLE
            Glide.with(requireContext())
                .load(notification.imageUrl)
                .into(imageView)
        } else {
            imageView.visibility = View.GONE
        }


        if (notification.linkUrl != null && notification.getLocalizedLinkText(language) != null) {
            linkView.visibility = View.VISIBLE
            linkView.text = notification.getLocalizedLinkText(language)
            linkView.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(notification.linkUrl))
                startActivity(intent)
            }
        } else {
            linkView.visibility = View.GONE
        }

        primaryButton.text = notification.getLocalizedPrimaryButton(language)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(notification.getLocalizedTitle(language))
            .setView(dialogView)
            .setCancelable(true)
            .create()

        primaryButton.setOnClickListener {
            notificationService.markNotificationAsSeen(notification.id)
            dialog.dismiss()
        }

        notification.getLocalizedSecondaryButton(language)?.let { buttonText ->
            secondaryButton.visibility = View.VISIBLE
            secondaryButton.text = buttonText
            secondaryButton.setOnClickListener {
                notificationService.markNotificationAsSeen(notification.id)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun setupUpdateBanner() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val updateChecker = UpdateChecker(requireContext())
                updateChecker.checkForUpdates().collect { status ->
                    if (!isAdded) return@collect

                    withContext(Dispatchers.Main) {
                        if (_binding == null) return@withContext
                        val hasAppUpdate = status.appUpdate?.let {
                            it.currentVersion != it.newVersion
                        } ?: false
                        val hasSystemUpdates = status.fileUpdates.any { it.needsUpdate }
                        val hasDbUpdates = status.dbUpdates.any { it.needsUpdate }

                        if (!hasAppUpdate && !hasSystemUpdates && !hasDbUpdates) {
                            binding.updateBanner.root.visibility = View.GONE
                            return@withContext
                        }

                        binding.updateBanner.root.visibility = View.VISIBLE

                        binding.updateBanner.updateMessage.text =
                            getString(R.string.update_available)

                        binding.updateBanner.buttonUpdate.setOnClickListener {
                            findNavController().navigate(R.id.nav_updates)
                        }

                        binding.updateBanner.buttonClose.setOnClickListener {
                            binding.updateBanner.root.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WiFiScannerFragment", "Error checking for updates", e)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.updateBanner.root.visibility = View.GONE
                }
            }
        }
    }

    private fun startWifiScan() {
        if (!isAdded || view == null) return
        if (hasRoot) {
            val cm = ChrootManager.get(requireContext())
            cm.resetMountFailedCooldown()
        }
        val useIwScan = hasRoot && hasChroot && (isIwScanSelected || scanInterface != "wlan0")

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    viewModel.clearResults()
                    wifiAdapter.clearDatabaseResults()
                    iwWifiAdapter.clearDatabaseResults()
                    if (useIwScan) startIwScan() else startWifiScanInternal()
                    hasScanned = true
                }
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQUEST_LOCATION_PERMISSION
                    )
                } else {
                    viewModel.clearResults()
                    wifiAdapter.clearDatabaseResults()
                    iwWifiAdapter.clearDatabaseResults()
                    if (useIwScan) startIwScan() else startWifiScanInternal()
                    hasScanned = true
                }
            }

            else -> {
                viewModel.clearResults()
                wifiAdapter.clearDatabaseResults()
                iwWifiAdapter.clearDatabaseResults()
                if (useIwScan) startIwScan() else startWifiScanInternal()
                hasScanned = true
            }
        }
    }

    private fun startWifiScanInternal() {
        if (_binding == null || !isAdded) return
        binding.buttonScanWifi.isEnabled = false
        wifiAdapter.clearDatabaseResults()
        iwWifiAdapter.clearDatabaseResults()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModel.startWifiScan()
        } else {
            viewModel.startLegacyWifiScan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                wifiAdapter.clearDatabaseResults()
                iwWifiAdapter.clearDatabaseResults()
                val useIwScan =
                    hasRoot && hasChroot && (isIwScanSelected || scanInterface != "wlan0")
                if (useIwScan) {
                    viewModel.clearResults()
                    wifiAdapter.clearDatabaseResults()
                    iwWifiAdapter.clearDatabaseResults()
                    startIwScan()
                } else {
                    startWifiScanInternal()
                }
                hasScanned = true
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.location_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
                if (_binding == null) return
                binding.swipeRefreshLayout.isRefreshing = false
                binding.buttonScanWifi.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        modePollingJob?.cancel()
        modePollingJob = null
        prefsListener?.let { listener ->
            try {
                val prefs =
                    requireContext().getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            } catch (e: Exception) {
                Log.w("WiFiScannerFragment", "Failed to unregister preference listener", e)
            }
        }
        prefsListener = null
        wlanInterfaceViewModel.stopPolling()
        _binding = null
    }

    private suspend fun loadInterfaces() {
        try {
            if (!hasChroot) {
                availableInterfaces = listOf(IwInterface("wlan0"))
                hasMultipleInterfaces = false
                scanInterface = "wlan0"
                isIwScanSelected = false
                updateAdapterForInterface()
                return
            }
            availableInterfaces = iwWifiManager.getAvailableInterfaces()
            hasMultipleInterfaces = availableInterfaces.size > 1

            val savedIface = iwWifiManager.getSavedSelectedInterface()
            if (savedIface != null && availableInterfaces.any { it.name == savedIface }) {
                scanInterface = savedIface
            } else {
                scanInterface = availableInterfaces.firstOrNull()?.name ?: "wlan0"
            }
            syncIwModeForNonWlan0()
            updateAdapterForInterface()
        } catch (e: Exception) {
            Log.e("WiFiScannerFragment", "Failed to load interfaces", e)
            availableInterfaces = emptyList()
            hasMultipleInterfaces = false
        }
    }

    private fun setupInterfaceSpinner() {
        if (_binding == null) return
        if (!hasMultipleInterfaces) {
            binding.layoutInterfaceRow.visibility = View.GONE
            return
        }

        binding.layoutInterfaceRow.visibility = View.VISIBLE
        binding.spinnerInterface.isEnabled = hasRoot

        if (!hasRoot) {
            Toast.makeText(
                requireContext(),
                getString(R.string.wifi_scanner_no_root_multiple),
                Toast.LENGTH_LONG
            ).show()
        }

        val displayNames = availableInterfaces.map { iface ->
            if (iface.type.isNotBlank()) "${iface.name} (${iface.type})" else iface.name
        }.toMutableList()

        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerInterface.adapter = adapter

        val selectedIndex = displayNames.indexOfFirst { it.startsWith(scanInterface) }
        if (selectedIndex >= 0) {
            binding.spinnerInterface.setSelection(selectedIndex)
        }

        binding.spinnerInterface.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val displayName = parent?.getItemAtPosition(position) as? String ?: return
                    val ifaceName = displayName.split(" ")[0]
                    if (ifaceName != scanInterface) {
                        scanInterface = ifaceName
                        iwWifiManager.saveSelectedInterface(scanInterface)
                        syncIwModeForNonWlan0()
                        updateAdapterForInterface()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.wifi_scanner_interface_selected, scanInterface),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        val prefs = requireContext().getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
        prefsListener?.let { oldListener ->
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(oldListener)
            } catch (e: Exception) {
                Log.w("WiFiScannerFragment", "Failed to unregister old preference listener", e)
            }
        }
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                if (_binding == null) return@OnSharedPreferenceChangeListener
                if (key == KEY_SCAN_IFACE) {
                    val newIface = sharedPreferences.getString(key, "wlan0") ?: "wlan0"
                    if (newIface != scanInterface && availableInterfaces.any { it.name == newIface }) {
                        scanInterface = newIface
                        updateAdapterForInterface()
                        val idx = displayNames.indexOfFirst { it.startsWith(newIface) }
                        if (idx >= 0) {
                            binding.spinnerInterface.setSelection(idx)
                        }
                    }
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefsListener = listener
    }

    private fun setupMonitorModeBanner() {
        if (_binding == null) return
        binding.monitorModeBanner.buttonSwitchManaged.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val success =
                    iwWifiManager.setInterfaceMode(scanInterface, IwWifiManager.MODE_MANAGED)
                if (success) {
                    binding.monitorModeBanner.root.visibility = View.GONE
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.ws_switch_managed, scanInterface),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.ws_switch_failed, scanInterface),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        binding.monitorModeBanner.buttonCloseMonitor.setOnClickListener {
            binding.monitorModeBanner.root.visibility = View.GONE
        }
    }

    private fun startModePolling() {
        if (!hasRoot || !hasChroot) return
        modePollingJob?.cancel()
        modePollingJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val mode = iwWifiManager.getInterfaceMode(scanInterface)
                    if (_binding != null) {
                        if (mode == IwWifiManager.MODE_MONITOR) {
                            binding.monitorModeBanner.root.visibility = View.VISIBLE
                            binding.monitorModeBanner.monitorModeMessage.text =
                                getString(R.string.monitor_mode_warning, scanInterface)
                        } else {
                            binding.monitorModeBanner.root.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    Log.e("WiFiScannerFragment", "Mode polling error", e)
                }
                delay(MODE_POLL_INTERVAL_MS)
            }
        }
    }

    private fun startIwScan() {
        if (view == null || !isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val iwNetworks = iwWifiManager.scanWifiNetworks(scanInterface)
                if (iwNetworks.isNotEmpty()) {
                    viewModel.setIwScanResults(iwNetworks)
                } else {
                    Log.w(
                        "WiFiScannerFragment",
                        "IW scan returned empty results, falling back to Android WiFi scan"
                    )
                    startWifiScanInternal()
                }
            } catch (e: Exception) {
                Log.e("WiFiScannerFragment", "IW scan failed, falling back to WifiManager", e)
                startWifiScanInternal()
            }
        }
    }

    private fun updateAdapterForInterface() {
        if (_binding == null) return
        val useIwAdapter = hasRoot && hasChroot && isIwScanSelected
        binding.recyclerViewWifi.adapter = if (useIwAdapter) iwWifiAdapter else wifiAdapter
    }

    private fun updateScanModeToggleVisibility() {
        if (_binding == null) return
        val iwAvailable = hasRoot && hasChroot
        binding.scanModeToggle.visibility = if (iwAvailable) View.VISIBLE else View.GONE
        if (!iwAvailable) {
            isIwScanSelected = false
        }
    }

    private fun syncIwModeForNonWlan0() {
        if (_binding == null || !hasRoot || !hasChroot) return
        if (scanInterface != "wlan0" && !isIwScanSelected) {
            isIwScanSelected = true
            isUpdatingScanMode = true
            binding.scanModeToggle.check(R.id.button_scan_iw)
            isUpdatingScanMode = false
        }
    }

    private fun switchToStandardInterface() {
        if (scanInterface == "wlan0") return
        scanInterface = "wlan0"
        iwWifiManager.saveSelectedInterface("wlan0")
        selectInterfaceInSpinner("wlan0")
    }

    private fun selectInterfaceInSpinner(ifaceName: String) {
        if (_binding == null) return
        val adapter = binding.spinnerInterface.adapter as? ArrayAdapter<String> ?: return
        val idx = (0 until adapter.count).indexOfFirst {
            (adapter.getItem(it) ?: "").startsWith(ifaceName)
        }
        if (idx >= 0) binding.spinnerInterface.setSelection(idx, false)
    }

    private fun getActiveScanResults(): List<ScanResult> {
        if (_binding == null) return emptyList()
        return if (binding.recyclerViewWifi.adapter == iwWifiAdapter) {
            iwWifiAdapter.getNetworkList().map { network ->
                createScanResult(
                    ssid = network.ssid,
                    bssid = network.bssid,
                    capabilities = buildIwCapabilities(network),
                    level = network.signalStrength,
                    frequency = network.frequency.toIntOrNull() ?: 2412
                )
            }
        } else {
            wifiAdapter.getWifiList()
        }
    }

    private fun mergeResultsToBothAdapters(results: Map<String, List<NetworkDatabaseResult>>) {
        wifiAdapter.mergeDatabaseResults(results)
        iwWifiAdapter.mergeDatabaseResults(results)
    }

    private fun showProgressBar() {
        if (_binding == null) return
        binding.progressBarDatabaseCheck.startAnimation()
    }

    private fun hideProgressBar() {
        if (_binding == null) return

        val isDbLoading = dbSetupViewModel.isLoading.value ?: false
        val isViewModelChecking = viewModel.isChecking.value ?: false

        if (!isDbLoading && !isViewModelChecking) {
            binding.progressBarDatabaseCheck.stopAnimation()
        }
    }

    private fun showToastWithDuration(message: String, durationMs: Long) {
        val duration = if (durationMs <= 2000) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        Toast.makeText(requireContext(), message, duration).show()
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View,
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        showCustomContextMenu()
    }

    private fun showCustomContextMenu() {
        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_context_menu, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        val theme = requireContext().theme
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
        val colorPrimary = typedValue.data

        fun TextView.tintDrawableStart(@DrawableRes drawableRes: Int) {
            val drawable = AppCompatResources.getDrawable(context, drawableRes)?.mutate()
            drawable?.let {
                DrawableCompat.setTint(it, colorPrimary)
                setCompoundDrawablesWithIntrinsicBounds(it, null, null, null)
            }
        }

        val connectionHelper = WiFiConnectionHelper(requireContext())
        val isConnectedViaApp = selectedWifi?.let { wifi ->
            connectionHelper.isConnectedViaApp(wifi.SSID, wifi.BSSID)
        } ?: false
        val currentSsid = selectedWifi?.SSID ?: selectedIwNetwork?.ssid
        val currentBssid = selectedWifi?.BSSID ?: selectedIwNetwork?.bssid
        val hasSelectedNetwork = selectedWifi != null || selectedIwNetwork != null

        dialogView.findViewById<TextView>(R.id.action_copy_ssid)?.apply {
            tintDrawableStart(R.drawable.ic_content_copy)
            setOnClickListener {
                copyToClipboard(getString(R.string.ssid), currentSsid)
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_copy_bssid)?.apply {
            tintDrawableStart(R.drawable.ic_content_copy)
            setOnClickListener {
                copyToClipboard(getString(R.string.bssid), currentBssid)
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_connect_with_password)?.apply {
            tintDrawableStart(R.drawable.ic_wifi)
            val isOpen = selectedWifi?.let { isOpenNetwork(it.capabilities) } ?: true
            visibility = if (selectedWifi == null || isOpen) View.GONE else View.VISIBLE
            Log.d(
                "WiFiScannerFragment",
                "[menu] connect_with_password: ssid='${selectedWifi?.SSID}' caps='${selectedWifi?.capabilities}' " +
                        "selectedNull=${selectedWifi == null} isConnectedViaApp=$isConnectedViaApp isOpen=$isOpen " +
                        "visibility=${if (visibility == View.VISIBLE) "VISIBLE" else "GONE"} " +
                        "SDK=${Build.VERSION.SDK_INT}(${Build.VERSION.RELEASE}) ${Build.MANUFACTURER} ${Build.MODEL}"
            )
            setOnClickListener {
                selectedWifi?.let { wifi ->
                    showPasswordDialog(wifi)
                    dialog.dismiss()
                } ?: Toast.makeText(
                    requireContext(),
                    getString(R.string.ws_network_not_selected),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_disconnect_network)?.apply {
            tintDrawableStart(R.drawable.ic_cancel)
            visibility = if (selectedWifi != null && isConnectedViaApp) View.VISIBLE else View.GONE
            setOnClickListener {
                selectedWifi?.let { wifi ->
                    disconnectFromNetwork(wifi)
                    dialog.dismiss()
                }
            }
        }

        dialogView.findViewById<TextView>(R.id.action_forget_network)?.apply {
            tintDrawableStart(R.drawable.ic_content_copy)
            visibility = if (selectedWifi != null && isConnectedViaApp) View.VISIBLE else View.GONE
            setOnClickListener {
                selectedWifi?.let { wifi ->
                    forgetNetwork(wifi)
                    dialog.dismiss()
                }
            }
        }

        dialogView.findViewById<TextView>(R.id.action_check_vendor)?.apply {
            tintDrawableStart(R.drawable.ic_info)
            setOnClickListener {
                currentBssid?.let { showVendorDialog(it.uppercase(Locale.getDefault())) }
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_calculate_distance)?.apply {
            tintDrawableStart(R.drawable.ic_analytics)
            visibility = if (hasSelectedNetwork) View.VISIBLE else View.GONE
            setOnClickListener {
                when {
                    selectedWifi != null -> showDistanceDialog(selectedWifi!!)
                    selectedIwNetwork != null -> showDistanceDialog(selectedIwNetwork!!)
                }
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_connect_wps_pin)?.apply {
            tintDrawableStart(R.drawable.wifi_protected_setup_24px)
            visibility = if (selectedWifi != null) View.VISIBLE else View.GONE
            setOnClickListener {
                selectedWifi?.let { wifi ->
                    showWpsConnectMenu(wifi)
                    dialog.dismiss()
                } ?: Toast.makeText(
                    requireContext(),
                    getString(R.string.ws_network_not_selected),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_generate_wps)?.apply {
            tintDrawableStart(R.drawable.ic_key)
            setOnClickListener {
                val intent = Intent(requireContext(), WpsGeneratorActivity::class.java).apply {
                    putExtra("BSSID", currentBssid)
                }
                Log.d("WiFiScannerFragment", "Selected BSSID: $currentBssid")
                startActivity(intent)
                dialog.dismiss()
            }
        }

        val isRootForBrute = requireContext().getSharedPreferences(
            "com.lsd.wififrankenstein", Context.MODE_PRIVATE
        ).getBoolean("enable_root", false)

        dialogView.findViewById<TextView>(R.id.action_wps_brute)?.apply {
            tintDrawableStart(R.drawable.pin_24px)
            visibility = if (isRootForBrute) View.VISIBLE else View.GONE
            setOnClickListener {
                val bssid = currentBssid ?: return@setOnClickListener
                val ssid = selectedWifi?.SSID ?: selectedIwNetwork?.ssid ?: bssid
                dialog.dismiss()

                val infoView = layoutInflater.inflate(R.layout.dialog_bruteforce_progress, null)
                infoView.findViewById<TextView>(R.id.textBruteInfo).text = "$ssid\n$bssid"

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.wps_brute_force)
                    .setView(infoView)
                    .setPositiveButton(R.string.run_in_background) { _, _ ->
                        ForegroundAttackService.startWpsBruteForce(requireContext(), bssid)
                        Toast.makeText(
                            requireContext(),
                            R.string.wps_brute_force_start,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_psk_brute)?.apply {
            tintDrawableStart(R.drawable.ic_lock_open)
            setOnClickListener {
                val ssid = selectedWifi?.SSID ?: selectedIwNetwork?.ssid ?: ""
                val bssid = currentBssid ?: return@setOnClickListener
                if (ssid.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.ws_ssid_not_available), Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                startPskBruteForce(ssid, bssid)
            }
        }

        dialogView.findViewById<TextView>(R.id.action_generate_qr)?.apply {
            tintDrawableStart(R.drawable.ic_qr_code)
            visibility = if (hasSelectedNetwork) View.VISIBLE else View.GONE
            setOnClickListener {
                val ssid = selectedWifi?.SSID ?: selectedIwNetwork?.ssid ?: ""
                val bssid = currentBssid ?: ""
                val capabilities =
                    selectedWifi?.capabilities ?: selectedIwNetwork?.capabilities ?: ""

                val databaseResults =
                    viewModel.databaseResults.value?.get(bssid.lowercase(Locale.ROOT))
                val password = databaseResults?.firstNotNullOfOrNull { result ->
                    result.databaseInfo["WiFiKey"] as? String
                        ?: result.databaseInfo["key"] as? String
                }?.takeIf { it.isNotBlank() }

                val finalPassword = password ?: ""
                val security = if (finalPassword.isNotEmpty()) {
                    QrNavigationHelper.determineSecurityType(capabilities)
                } else {
                    "NONE"
                }

                QrNavigationHelper.navigateToQrGenerator(
                    this@WiFiScannerFragment,
                    ssid,
                    finalPassword,
                    security
                )
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_show_iw_details)?.apply {
            tintDrawableStart(R.drawable.ic_info)
            visibility = if (selectedIwNetwork != null) View.VISIBLE else View.GONE
            setOnClickListener {
                selectedIwNetwork?.let { network ->
                    dialog.dismiss()
                    val detailsFragment = IwWifiDetailsFragment.newInstance(network, scanInterface)
                    detailsFragment.show(childFragmentManager, "network_details")
                }
            }
        }

        dialogView.findViewById<TextView>(R.id.action_capture_handshake)?.apply {
            tintDrawableStart(R.drawable.grid_3x3_24px)
            visibility = if (hasChroot) View.VISIBLE else View.GONE
            setOnClickListener {
                navigateToHandshakeCapture()
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_pixie_dust)?.apply {
            tintDrawableStart(R.drawable.ic_key)
            visibility = if (isRootForBrute) View.VISIBLE else View.GONE
            setOnClickListener {
                navigateToPixieDust()
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_scan_router)?.apply {
            tintDrawableStart(R.drawable.router_24px)
            visibility =
                if (isConnectedToSelectedNetwork() && hasChroot) View.VISIBLE else View.GONE
            setOnClickListener {
                navigateToRouterScan()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showWpsConnectMenu(wifi: ScanResult) {
        val prefs = requireContext().getSharedPreferences(
            "com.lsd.wififrankenstein",
            Context.MODE_PRIVATE
        )
        val isRootEnabled = prefs.getBoolean("enable_root", false)

        val items = listOf(
            BottomSheetMenuItem(
                R.id.action_connect_wps,
                getString(R.string.connect_wps_button),
                R.drawable.wifi_protected_setup_24px
            ),
            BottomSheetMenuItem(
                R.id.action_connect_wps_pin,
                getString(R.string.wps_connect_non_root),
                R.drawable.wifi_protected_setup_24px
            ),
            BottomSheetMenuItem(
                R.id.action_connect_wps_root,
                getString(R.string.connect_wps_root_menu),
                R.drawable.wifi_protected_setup_24px,
                visible = isRootEnabled
            ),
            BottomSheetMenuItem(
                R.id.action_connect_wps_chroot,
                getString(R.string.connect_wps_chroot_menu),
                R.drawable.wifi_protected_setup_24px,
                visible = hasChroot
            )
        )

        BottomSheetMenu.show(
            requireContext(),
            title = getString(R.string.wps_actions),
            items = items
        ) { item ->
            when (item.id) {
                R.id.action_connect_wps -> connectUsingWPSButton(wifi)
                R.id.action_connect_wps_pin -> showWpsPinDialog(wifi)
                R.id.action_connect_wps_root -> connectUsingWpsRoot(wifi)
                R.id.action_connect_wps_chroot -> showWpsChrootPinDialog(wifi)
            }
        }
    }

    private fun navigateToHandshakeCapture() {
        try {
            val bundle = Bundle().apply {
                putString("bssid", selectedWifi?.BSSID ?: selectedIwNetwork?.bssid)
                putString("ssid", selectedWifi?.SSID ?: selectedIwNetwork?.ssid)
                putString(
                    "channel",
                    selectedIwNetwork?.channel ?: selectedWifi?.frequency?.let {
                        frequencyToChannel(it)
                    }?.toString().orEmpty()
                )
                putString("interface", scanInterface)
            }
            findNavController().navigate(R.id.nav_handshake_capture_selector, bundle)
        } catch (e: Exception) {
            Log.e("WiFiScannerFragment", "Navigation to handshake capture failed", e)
        }
    }

    private fun startPskBruteForce(ssid: String, bssid: String) {
        val nativeSupported = PskBruteForceEngines.isNativeSupported(requireContext())
        val chrootReady = isPskChrootReady()

        fun navigateToBruteforce(engine: String) {
            val bundle = Bundle().apply {
                putString("ssid", ssid)
                putString("bssid", bssid)
                putString("interface", scanInterface)
                putString("engine", engine)
            }
            try {
                findNavController().navigate(R.id.nav_bruteforce, bundle)
            } catch (e: Exception) {
                Log.e("WiFiScannerFragment", "Navigation to PSK brute force failed", e)
            }
        }

        val items = mutableListOf<BottomSheetMenuItem>()
        if (nativeSupported) {
            items.add(
                BottomSheetMenuItem(
                    R.id.action_psk_backend_native,
                    getString(R.string.psk_engine_native),
                    R.drawable.ic_wifi
                )
            )
        }
        if (chrootReady) {
            items.add(
                BottomSheetMenuItem(
                    R.id.action_psk_backend_chroot,
                    getString(R.string.psk_engine_chroot),
                    R.drawable.ic_wps
                )
            )
        }

        when {
            nativeSupported && !chrootReady -> navigateToBruteforce("NATIVE")
            !nativeSupported && chrootReady -> navigateToBruteforce("CHROOT")
            !nativeSupported && !chrootReady -> {
                Toast.makeText(
                    requireContext(),
                    R.string.psk_engine_chroot_unsupported,
                    Toast.LENGTH_LONG
                ).show()
            }

            else -> {
                BottomSheetMenu.show(
                    requireContext(),
                    title = getString(R.string.psk_engine_label),
                    items = items
                ) { item ->
                    when (item.id) {
                        R.id.action_psk_backend_native -> navigateToBruteforce("NATIVE")
                        R.id.action_psk_backend_chroot -> navigateToBruteforce("CHROOT")
                    }
                }
            }
        }
    }

    private fun isPskChrootReady(): Boolean {
        return try {
            val chrootType = ChrootManager.get(requireContext()).getChrootType()
            chrootType is ChrootType.Root ||
                    (chrootType is ChrootType.Rootless &&
                            RootlessManager(requireContext()).isSetupCompleted())
        } catch (e: Exception) {
            Log.e("WiFiScannerFragment", "PSK chroot readiness check failed", e)
            false
        }
    }

    private fun navigateToPixieDust() {
        try {
            val bundle = Bundle().apply {
                putString("bssid", selectedWifi?.BSSID ?: selectedIwNetwork?.bssid)
                putString("ssid", selectedWifi?.SSID ?: selectedIwNetwork?.ssid)
                putString("interface", scanInterface)
            }
            findNavController().navigate(R.id.nav_pixie_dust, bundle)
        } catch (e: Exception) {
            Log.e("WiFiScannerFragment", "Navigation to PixieDust failed", e)
        }
    }

    private fun isConnectedToSelectedNetwork(): Boolean {
        return try {
            val wifiManager = requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo ?: return false
            val currentBssid = info.bssid?.uppercase(Locale.ROOT)
            val currentSsid = info.ssid?.trim('"')
            val targetBssid =
                (selectedWifi?.BSSID ?: selectedIwNetwork?.bssid)?.uppercase(Locale.ROOT)
            val targetSsid = selectedWifi?.SSID ?: selectedIwNetwork?.ssid
            (targetBssid != null && targetBssid.isNotEmpty() && currentBssid == targetBssid) ||
                    (targetSsid != null && targetSsid.isNotEmpty() && currentSsid == targetSsid)
        } catch (e: Exception) {
            Log.e("WiFiScannerFragment", "Connected check failed", e)
            false
        }
    }

    private fun navigateToRouterScan() {
        try {
            val wifiManager = requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val gateway = wifiManager.dhcpInfo?.gateway ?: 0
            if (gateway == 0) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.ws_router_ip_not_available),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            val bundle = Bundle().apply {
                putString("target_ip", intToIp(gateway))
            }
            findNavController().navigate(R.id.nav_router_scan, bundle)
        } catch (e: Exception) {
            Log.e("WiFiScannerFragment", "Navigation to RouterScan failed", e)
        }
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    private fun frequencyToChannel(freq: Int): Int {
        return when {
            freq == 2484 -> 14
            freq in 2412..2484 -> (freq - 2407) / 5
            freq in 5000..5900 -> (freq - 5000) / 5
            freq in 5925..7125 -> (freq - 5950) / 5
            else -> 0
        }
    }

    private fun disconnectFromNetwork(scanResult: ScanResult) {
        Log.d(
            "WiFiScannerFragment",
            "[disconnectFromNetwork] ssid='${scanResult.SSID}' bssid='${scanResult.BSSID}'"
        )
        val connectionHelper = WiFiConnectionHelper(requireContext())

        connectionHelper.disconnectAndForgetNetwork(
            scanResult.SSID,
            scanResult.BSSID,
            object : WiFiConnectionHelper.DisconnectionCallback {
                override fun onDisconnectionSuccess() {
                    Log.d("WiFiScannerFragment", "[disconnectFromNetwork] onDisconnectionSuccess")
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.disconnected_successfully),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onDisconnectionFailed(error: String) {
                    Log.d(
                        "WiFiScannerFragment",
                        "[disconnectFromNetwork] onDisconnectionFailed error='$error'"
                    )
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.disconnect_failed, error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onNetworkForgotten() {
                    Log.d("WiFiScannerFragment", "[disconnectFromNetwork] onNetworkForgotten")
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.network_forgotten),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun isOpenNetwork(capabilities: String?): Boolean {
        if (capabilities.isNullOrBlank()) return false
        if (!capabilities.contains("[")) return false
        val hasSecurity = listOf(
            "WPA3", "WPA2", "WPA", "WEP", "PSK", "SAE", "EAP", "OWE"
        ).any { capabilities.contains(it, ignoreCase = true) }
        return !hasSecurity
    }

    private fun showPasswordDialog(scanResult: ScanResult) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_password_input, null)
        val passwordEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextPassword)
        val networkInfoText = dialogView.findViewById<TextView>(R.id.networkInfoText)

        networkInfoText.text =
            getString(R.string.network_info_format, scanResult.SSID, scanResult.BSSID)

        val isPskNetwork = listOf("WPA3", "WPA2", "WPA", "PSK").any {
            scanResult.capabilities.contains(it, ignoreCase = true)
        }

        Log.d(
            "WiFiScannerFragment",
            "[showPasswordDialog] ssid='${scanResult.SSID}' caps='${scanResult.capabilities}' isPskNetwork=$isPskNetwork " +
                    "SDK=${Build.VERSION.SDK_INT}(${Build.VERSION.RELEASE}) ${Build.MANUFACTURER} ${Build.MODEL}"
        )

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.connect_with_password))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.connect)) { _, _ ->
                val password = passwordEditText.text.toString()
                Log.d(
                    "WiFiScannerFragment",
                    "[showPasswordDialog] connect pressed ssid='${scanResult.SSID}' pwdLen=${password.length} " +
                            "isBlank=${password.isBlank()} isPskNetwork=$isPskNetwork"
                )
                if (password.isNotBlank()) {
                    if (isPskNetwork && (password.length < 8 || password.length > 63)) {
                        Log.d(
                            "WiFiScannerFragment",
                            "[showPasswordDialog] password length invalid: ${password.length}"
                        )
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.password_length_invalid),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        connectToWiFiWithPassword(scanResult, password)
                    }
                } else {
                    Log.d("WiFiScannerFragment", "[showPasswordDialog] password is blank")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.wifi_password_required),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.show()

        passwordEditText.requestFocus()
        val imm =
            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(passwordEditText, InputMethodManager.SHOW_IMPLICIT)
    }


    private fun forgetNetwork(scanResult: ScanResult) {
        Log.d(
            "WiFiScannerFragment",
            "[forgetNetwork] ssid='${scanResult.SSID}' bssid='${scanResult.BSSID}'"
        )
        val connectionHelper = WiFiConnectionHelper(requireContext())

        connectionHelper.disconnectAndForgetNetwork(
            scanResult.SSID,
            scanResult.BSSID,
            object : WiFiConnectionHelper.DisconnectionCallback {
                override fun onDisconnectionSuccess() {
                    Log.d("WiFiScannerFragment", "[forgetNetwork] onDisconnectionSuccess")
                }

                override fun onDisconnectionFailed(error: String) {
                    Log.d(
                        "WiFiScannerFragment",
                        "[forgetNetwork] onDisconnectionFailed error='$error'"
                    )
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.disconnect_failed, error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onNetworkForgotten() {
                    Log.d("WiFiScannerFragment", "[forgetNetwork] onNetworkForgotten")
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.network_forgotten),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    private fun connectToWiFiWithPassword(scanResult: ScanResult, password: String) {
        val connectionHelper = WiFiConnectionHelper(requireContext())

        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.connecting))
            .setView(ProgressBar(requireContext()))
            .setCancelable(false)
            .create()

        lifecycleScope.launch {
            Log.d(
                "WiFiScannerFragment",
                "[connectToWiFiWithPassword] ssid='${scanResult.SSID}' caps='${scanResult.capabilities}' " +
                        "pwdLen=${password.length} SDK=${Build.VERSION.SDK_INT}(${Build.VERSION.RELEASE}) " +
                        "${Build.MANUFACTURER} ${Build.MODEL}"
            )
            try {
                val success = connectionHelper.connectToNetwork(
                    scanResult,
                    password,
                    object : WiFiConnectionHelper.ConnectionCallback {
                        override fun onConnectionStarted() {
                            Log.d(
                                "WiFiScannerFragment",
                                "[connectToWiFiWithPassword] onConnectionStarted ssid='${scanResult.SSID}'"
                            )
                            requireActivity().runOnUiThread {
                                if (!progressDialog.isShowing) {
                                    progressDialog.show()
                                }
                            }
                        }

                        override fun onSuggestionApprovalRequired() {
                            Log.d(
                                "WiFiScannerFragment",
                                "[connectToWiFiWithPassword] onSuggestionApprovalRequired ssid='${scanResult.SSID}'"
                            )
                            requireActivity().runOnUiThread {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.suggestion_approval_required),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onConnectionSuccess(ssid: String) {
                            Log.d(
                                "WiFiScannerFragment",
                                "[connectToWiFiWithPassword] onConnectionSuccess ssid='$ssid'"
                            )
                            requireActivity().runOnUiThread {
                                dismissConnectProgress(progressDialog)
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.connection_successful, ssid),
                                    Toast.LENGTH_LONG
                                ).show()
                                viewModel.refreshWifiList()
                            }
                        }

                        override fun onConnectionFailed(error: String) {
                            Log.d(
                                "WiFiScannerFragment",
                                "[connectToWiFiWithPassword] onConnectionFailed error='$error'"
                            )
                            requireActivity().runOnUiThread {
                                dismissConnectProgress(progressDialog)
                                if (error.contains("Location", ignoreCase = true)) {
                                    showLocationDisabledDialog()
                                } else {
                                    Toast.makeText(
                                        requireContext(),
                                        getString(R.string.connection_failed, error),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }

                        override fun onConnectionTimeout() {
                            Log.d(
                                "WiFiScannerFragment",
                                "[connectToWiFiWithPassword] onConnectionTimeout ssid='${scanResult.SSID}'"
                            )
                            requireActivity().runOnUiThread {
                                dismissConnectProgress(progressDialog)
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.connection_timeout),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )

                Log.d("WiFiScannerFragment", "[connectToWiFiWithPassword] result success=$success")
                if (!success) {
                    Log.d("WiFiScannerFragment", "Connection attempt failed")
                }
            } catch (e: Exception) {
                Log.e(
                    "WiFiScannerFragment",
                    "[connectToWiFiWithPassword] EXCEPTION: ${e.message}",
                    e
                )
                requireActivity().runOnUiThread {
                    dismissConnectProgress(progressDialog)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.connection_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun dismissConnectProgress(dialog: android.app.Dialog) {
        if (dialog.isShowing) {
            dialog.dismiss()
        }
    }

    private fun connectUsingWpsRoot(network: ScanResult) {
        val methodSelector = WpsMethodSelector(
            requireContext(),
            object : WpsRootConnectHelper.WpsConnectCallbacks {
                override fun onConnectionProgress(message: String) {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onConnectionSuccess(ssid: String) {
                    requireActivity().runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.wps_root_connection_successful, ssid),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onConnectionFailed(error: String) {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onLogEntry(message: String) {
                    Log.d("WpsRootConnect", message)
                }

                override fun onWpsResult(pin: String?, psk: String?) {
                    requireActivity().runOnUiThread {
                        showWpsResultDialog(network.SSID, pin, psk)
                    }
                }
            }
        )

        val databaseResults =
            viewModel.databaseResults.value?.get(network.BSSID?.lowercase(Locale.ROOT) ?: "")
        val wpsPin = databaseResults?.firstNotNullOfOrNull { result ->
            result.databaseInfo["WPSPIN"]?.toString()
                ?: result.databaseInfo["wps_pin"]?.toString()
                ?: result.databaseInfo["wps"]?.toString()
        }?.takeIf { it.isNotBlank() && it != "0" }

        methodSelector.showMethodSelection(network, wpsPin)
    }

    private fun showWpsChrootPinDialog(scanResult: ScanResult) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_wps_pin, null)
        val wpsPinEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextWpsPin)

        wpsPinEditText.hint = getString(R.string.wps_pin_optional)

        val databaseResults =
            viewModel.databaseResults.value?.get(scanResult.BSSID?.lowercase(Locale.ROOT) ?: "")
        val databasePin = databaseResults?.firstNotNullOfOrNull { result ->
            result.databaseInfo["WPSPIN"]?.toString()
                ?: result.databaseInfo["wps_pin"]?.toString()
                ?: result.databaseInfo["wps"]?.toString()
        }?.takeIf { it.isNotBlank() && it != "0" }

        if (!databasePin.isNullOrEmpty()) {
            wpsPinEditText.setText(databasePin)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wps_chroot_pin_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.connect) { _, _ ->
                val wpsPin = wpsPinEditText.text.toString().trim()
                connectUsingWpsChroot(scanResult, wpsPin)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()
    }

    private fun connectUsingWpsChroot(network: ScanResult, wpsPin: String) {
        val runner = WpsChrootConnectRunner(requireContext())
        lifecycleScope.launch {
            runner.connectToNetworkWps(
                network = network,
                wpsPin = wpsPin,
                interfaceName = scanInterface,
                callbacks = object : WpsRootConnectHelper.WpsConnectCallbacks {
                    override fun onConnectionProgress(message: String) {
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onConnectionSuccess(ssid: String) {
                        requireActivity().runOnUiThread {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.wps_root_connection_successful, ssid),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onConnectionFailed(error: String) {
                        requireActivity().runOnUiThread {
                            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onLogEntry(message: String) {
                        Log.d("WpsChrootConnect", message)
                    }

                    override fun onWpsResult(pin: String?, psk: String?) {
                        requireActivity().runOnUiThread {
                            showWpsResultDialog(network.SSID, pin, psk)
                        }
                    }
                }
            )
        }
    }

    private fun showWpsResultDialog(ssid: String?, pin: String?, psk: String?) {
        val pinText = pin?.takeIf { it.isNotEmpty() } ?: getString(R.string.wps_result_none)
        val pskText = psk?.takeIf { it.isNotEmpty() } ?: getString(R.string.wps_result_none)
        val ssidText = ssid?.takeIf { it.isNotEmpty() } ?: getString(R.string.wps_result_unknown)

        val content = getString(R.string.wps_result_format, ssidText, pinText, pskText)
        val copyText = getString(R.string.wps_result_copy_format, ssidText, pinText, pskText)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wps_result_title)
            .setMessage(content)
            .setPositiveButton(R.string.wps_result_copy) { _, _ ->
                copyToClipboard(getString(R.string.wps_result_title), copyText)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun showWpsPinDialog(scanResult: ScanResult) {
        Log.d(TAG, "showWpsPinDialog: ssid='${scanResult.SSID}' bssid='${scanResult.BSSID}'")
        connectNonRootWps(scanResult)
    }

    private fun connectUsingWPSButton(scanResult: ScanResult) {
        Log.d(TAG, "connectUsingWPSButton: ssid='${scanResult.SSID}' bssid='${scanResult.BSSID}'")
        connectNonRootWps(scanResult)
    }

    private fun connectNonRootWps(scanResult: ScanResult) {
        Log.d(
            TAG,
            "connectNonRootWps: entry ssid='${scanResult.SSID}' bssid='${scanResult.BSSID}' " +
                    "sdk=${Build.VERSION.SDK_INT} (P=${Build.VERSION_CODES.P})"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.w(TAG, "connectNonRootWps: WPS not supported on Android 9+, aborting")
            Toast.makeText(
                requireContext(),
                getString(R.string.wps_not_supported),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CHANGE_WIFI_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "connectNonRootWps: CHANGE_WIFI_STATE permission missing, aborting")
            Toast.makeText(
                requireContext(),
                getString(R.string.change_wifi_state_permission_required),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        SystemWpsConnector.showModeSelection(requireContext(), databasePin = null) { wpsPin ->
            val modeName = when {
                wpsPin == null -> "PBC"
                wpsPin == "" -> "EMPTY_PIN"
                wpsPin == WpsMethodSelector.NULL_PIN_IDENTIFIER -> "NULL_PIN"
                else -> "REAL_PIN"
            }
            Log.d(
                TAG,
                "connectNonRootWps: mode selected mode=$modeName wpsPin='${wpsPin.orEmpty()}' " +
                        "bssid='${scanResult.BSSID}'"
            )
            val connector = SystemWpsConnector(requireContext())
            connector.connect(scanResult.BSSID, wpsPin, object : SystemWpsConnector.WpsCallbacks {
                override fun onStarted(pin: String?) {
                    val message = when {
                        wpsPin == null -> getString(R.string.wps_started_pbc)
                        wpsPin == "" -> getString(R.string.wps_started_empty_pin)
                        wpsPin == WpsMethodSelector.NULL_PIN_IDENTIFIER ->
                            getString(R.string.wps_started_null_pin)

                        else -> getString(R.string.wps_started_with_pin, wpsPin)
                    }
                    Log.d(
                        TAG,
                        "connectNonRootWps: onStarted pin='${pin.orEmpty()}' message='$message'"
                    )
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }

                override fun onSucceeded() {
                    Log.d(TAG, "connectNonRootWps: WPS succeeded for '${scanResult.SSID}'")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.wps_succeeded),
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onFailed(reason: Int) {
                    Log.w(
                        TAG,
                        "connectNonRootWps: WPS failed reason=$reason for '${scanResult.SSID}'"
                    )
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.wps_failed, reason),
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onError(message: String) {
                    Log.e(TAG, "connectNonRootWps: WPS error: $message")
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    private fun copyToClipboard(label: String, text: String?) {
        val clipboard =
            requireContext().applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text ?: "")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(
            requireContext(),
            getString(R.string.copied_to_clipboard, label),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showVendorDialog(bssid: String) {
        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_vendor_info, null)
        val dialogTitleView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_title, null) as TextView

        val bssidTextView: TextView = dialogView.findViewById(R.id.bssidTextView)
        val localVendor1TextView: TextView =
            dialogView.findViewById(R.id.localVendorSource1TextView)
        val onlineVendor1TextView: TextView =
            dialogView.findViewById(R.id.onlineVendorSource1TextView)
        val onlineVendor2TextView: TextView =
            dialogView.findViewById(R.id.onlineVendorSource2TextView)

        bssidTextView.text = bssid

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setCustomTitle(dialogTitleView)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .create()

        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.layoutParams =
            (positiveButton.layoutParams as LinearLayout.LayoutParams).apply {
                gravity = Gravity.CENTER
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }

        val hexBssid = bssid.replace(":", "")
        val formattedBSSID = if (hexBssid.length >= 6) hexBssid.substring(0, 6) else hexBssid

        lifecycleScope.launch {
            updateVendorInfo(localVendor1TextView) {
                VendorChecker.checkVendorLocalSource1(
                    requireContext(),
                    formattedBSSID
                )
            }
            updateVendorInfo(onlineVendor1TextView) {
                VendorChecker.checkVendorOnlineSource1(
                    formattedBSSID
                )
            }
            updateVendorInfo(onlineVendor2TextView) {
                VendorChecker.checkVendorOnlineSource2(
                    requireContext(),
                    formattedBSSID,
                    dbSetupViewModel.getWifiApiDatabases()
                )
            }
        }

        listOf(
            localVendor1TextView,
            onlineVendor1TextView,
            onlineVendor2TextView
        ).forEach { textView ->
            textView.setOnClickListener { copyToClipboard(getString(R.string.ws_vendor), textView.text.toString()) }
        }
    }

    private suspend fun updateVendorInfo(textView: TextView, vendorChecker: suspend () -> String) {
        val vendor = withContext(Dispatchers.IO) { vendorChecker() }
        withContext(Dispatchers.Main) {
            textView.text = vendor
            textView.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    android.R.color.primary_text_light
                )
            )
        }
    }

    private fun showDistanceDialog(scanResult: ScanResult) {
        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_distance, null)
        val distanceTextView: TextView = dialogView.findViewById(R.id.distanceTextView)
        val correctionFactorTextView: TextView = dialogView.findViewById(R.id.correction_factor)
        val buttonMinus: Button = dialogView.findViewById(R.id.button_minus)
        val buttonPlus: Button = dialogView.findViewById(R.id.button_plus)

        fun updateDistance() {
            val distance =
                calculateDistanceString(scanResult.frequency, scanResult.level, correctionFactor)
            distanceTextView.text = distance
        }

        correctionFactorTextView.text = correctionFactor.toString()

        buttonMinus.setOnClickListener {
            correctionFactor -= 0.1
            correctionFactorTextView.text = String.format("%.1f", correctionFactor)
            updateDistance()
        }

        buttonPlus.setOnClickListener {
            correctionFactor += 0.1
            correctionFactorTextView.text = String.format("%.1f", correctionFactor)
            updateDistance()
        }

        updateDistance()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .create()

        dialog.show()
    }

    private fun showDistanceDialog(iwNetwork: IwWifiNetwork) {
        val frequency = iwNetwork.frequency.toIntOrNull() ?: return
        val signalStr = iwNetwork.signal.replace(" dBm", "").trim()
        val level = signalStr.toIntOrNull() ?: return

        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_distance, null)
        val distanceTextView: TextView = dialogView.findViewById(R.id.distanceTextView)
        val correctionFactorTextView: TextView = dialogView.findViewById(R.id.correction_factor)
        val buttonMinus: Button = dialogView.findViewById(R.id.button_minus)
        val buttonPlus: Button = dialogView.findViewById(R.id.button_plus)

        fun updateDistance() {
            val distance =
                calculateDistanceString(frequency, level, correctionFactor)
            distanceTextView.text = distance
        }

        correctionFactorTextView.text = correctionFactor.toString()

        buttonMinus.setOnClickListener {
            correctionFactor -= 0.1
            correctionFactorTextView.text = String.format("%.1f", correctionFactor)
            updateDistance()
        }

        buttonPlus.setOnClickListener {
            correctionFactor += 0.1
            correctionFactorTextView.text = String.format("%.1f", correctionFactor)
            updateDistance()
        }

        updateDistance()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .create()

        dialog.show()
    }

    override fun onResume() {
        super.onResume()

        val isChecking = viewModel.isChecking.value ?: false
        if (!isChecking) {
            hideProgressBar()
        }

        viewModel.resetScanningState()
        if (_binding != null) {
            binding.swipeRefreshLayout.isRefreshing = false
            binding.buttonScanWifi.isEnabled = true
        }
        if (hasScanned) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                viewModel.refreshWifiList()
            }
        }
    }

    companion object {
        private const val TAG = "WiFiScannerFragment"
        private const val REQUEST_LOCATION_PERMISSION = 1
        private const val NOTIFICATION_URL =
            "https://raw.githubusercontent.com/LowSkillDeveloper/WIFI-Frankenstein/refs/heads/service/notification.json"
        private val WPS_PIN_REGEX = Regex("^\\d{8}$")
        private const val HANDSHAKE_PREFS = "handshake_capture"
        private const val KEY_SCAN_IFACE = "scan_interface"
        private const val MODE_POLL_INTERVAL_MS = 15000L
    }
}
