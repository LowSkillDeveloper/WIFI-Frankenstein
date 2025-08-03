package com.lsd.wififrankenstein.ui.wifiscanner

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
import android.util.TypedValue
import android.view.ContextMenu
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WpsGeneratorActivity
import com.lsd.wififrankenstein.databinding.FragmentWifiScannerBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.notification.NotificationMessage
import com.lsd.wififrankenstein.ui.notification.NotificationService
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel
import com.lsd.wififrankenstein.ui.updates.UpdateChecker
import com.lsd.wififrankenstein.ui.wpagenerator.WpaAlgorithmsHelper
import com.lsd.wififrankenstein.ui.wpsgenerator.WPSPin
import com.lsd.wififrankenstein.util.MacAddressUtils
import com.lsd.wififrankenstein.util.QrNavigationHelper
import com.lsd.wififrankenstein.util.VendorChecker
import com.lsd.wififrankenstein.util.WpsPinGenerator
import com.lsd.wififrankenstein.util.calculateDistanceString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.lsd.wififrankenstein.util.AnimatedLoadingBar
import com.lsd.wififrankenstein.util.WpsRootConnectHelper

class WiFiScannerFragment : Fragment() {

    private var _binding: FragmentWifiScannerBinding? = null
    private val binding get() = _binding!!

    private lateinit var notificationService: NotificationService

    private lateinit var wpaAlgorithmsHelper: WpaAlgorithmsHelper
    private lateinit var wpsPinGenerator: WpsPinGenerator

    private lateinit var wifiAdapter: WifiAdapter
    private val dbSetupViewModel: DbSetupViewModel by activityViewModels()
    private val viewModel: WiFiScannerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WiFiScannerViewModel(requireActivity().application) as T
            }
        }
    }


    private val settingsViewModel: SettingsViewModel by viewModels()

    private var selectedWifi: ScanResult? = null
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
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWifiScannerBinding.inflate(inflater, container, false)

        viewLifecycleOwner.lifecycleScope.launch {
            dbSetupViewModel.loadDbList()
        }

        initUI()
        observeViewModel()
        observeSettingsViewModel()

        return binding.root
    }

    private fun observeSettingsViewModel() {
        settingsViewModel.fullCleanup.observe(viewLifecycleOwner) { isFullCleanup ->
            if (isFullCleanup) {
                startWifiScan()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        notificationService = NotificationService(requireContext())
        checkForNotifications()

        wpaAlgorithmsHelper = WpaAlgorithmsHelper(requireContext())
        wpsPinGenerator = WpsPinGenerator()

        binding.searchTypeToggle.apply {
            check(R.id.button_search_mac)
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    viewModel.setSearchType(checkedId == R.id.button_search_mac)
                }
            }
        }

        if (shouldScanOnStartup() && !hasScanned) {
            startWifiScan()
        }
        if (shouldCheckUpdates()) {
            setupUpdateBanner()
        }
    }

    private fun generateWpaAlgorithms() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val networks = wifiAdapter.getWifiList()
                val allResults = mutableMapOf<String, MutableList<NetworkDatabaseResult>>()
                var totalKeys = 0

                networks.forEach { network ->
                    val results = withContext(Dispatchers.IO) {
                        wpaAlgorithmsHelper.generateKeys(network.SSID, network.BSSID)
                    }

                    if (results.isNotEmpty()) {
                        val networkResults = mutableListOf<NetworkDatabaseResult>()

                        results.filter { it.supportState == 2 }.forEach { wpaResult ->
                            // Создаем отдельную карточку для каждого ключа
                            wpaResult.keys.forEachIndexed { index, key ->
                                val keyDisplayName = if (wpaResult.keys.size > 1) {
                                    "${wpaResult.algorithm} (Key ${index + 1}/${wpaResult.keys.size})"
                                } else {
                                    wpaResult.algorithm
                                }

                                // Создаем копию WpaResult с одним ключом
                                val singleKeyResult = com.lsd.wififrankenstein.ui.wpagenerator.WpaResult(
                                    keys = listOf(key),
                                    algorithm = keyDisplayName,
                                    generationTime = wpaResult.generationTime,
                                    supportState = wpaResult.supportState
                                )

                                networkResults.add(NetworkDatabaseResult(
                                    network = network,
                                    databaseInfo = emptyMap(),
                                    databaseName = keyDisplayName,
                                    resultType = ResultType.WPA_ALGORITHM,
                                    wpaResult = singleKeyResult
                                ))

                                totalKeys++
                            }
                        }

                        if (networkResults.isNotEmpty()) {
                            allResults[network.BSSID.lowercase(Locale.ROOT)] = networkResults
                        }
                    }
                }

                wifiAdapter.updateDatabaseResults(allResults)
                hideProgressBar()

                Toast.makeText(
                    requireContext(),
                    getString(R.string.wpa_algorithms_generated, allResults.size),
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                hideProgressBar()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_general, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun generateWpsAlgorithms() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val networks = wifiAdapter.getWifiList()
                val allResults = mutableMapOf<String, MutableList<NetworkDatabaseResult>>()

                networks.forEach { network ->
                    val pins = withContext(Dispatchers.IO) {
                        generateWpsPinsForNetwork(network)
                    }

                    val filteredPins = pins.filter { pin ->
                        pin.sugg || shouldShowQuestionMark(pin)
                    }

                    if (filteredPins.isNotEmpty()) {
                        val networkResults = mutableListOf<NetworkDatabaseResult>()

                        val sortedPins = sortPinsByPriority(filteredPins)

                        sortedPins.forEach { wpsPin ->
                            networkResults.add(NetworkDatabaseResult(
                                network = network,
                                databaseInfo = emptyMap(),
                                databaseName = wpsPin.name,
                                resultType = ResultType.WPS_ALGORITHM,
                                wpsPin = wpsPin
                            ))
                        }

                        allResults[network.BSSID.lowercase(Locale.ROOT)] = networkResults
                    }
                }

                wifiAdapter.updateDatabaseResults(allResults)
                hideProgressBar()

                Toast.makeText(
                    requireContext(),
                    getString(R.string.wps_algorithms_generated, allResults.size),
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                hideProgressBar()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_general, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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

    private suspend fun generateWpsPinsForNetwork(network: ScanResult): List<WPSPin> = withContext(Dispatchers.IO) {
        val pins = mutableListOf<WPSPin>()

        val suggestedPins = wpsPinGenerator.generateSuggestedPins(network.BSSID, includeExperimental = true)
        val allPins = wpsPinGenerator.generateAllPins(network.BSSID, includeExperimental = true)

        suggestedPins.forEach { pinResult ->
            pins.add(WPSPin(
                mode = 0,
                name = pinResult.algorithm,
                pin = pinResult.pin,
                sugg = true,
                score = 1.0,
                additionalData = mapOf("mode" to pinResult.mode),
                isFrom3WiFi = false,
                isExperimental = pinResult.isExperimental
            ))
        }

        val nonSuggestedPins = allPins.filter { allPin ->
            suggestedPins.none { suggestedPin ->
                suggestedPin.pin == allPin.pin && suggestedPin.algorithm == allPin.algorithm
            }
        }

        nonSuggestedPins.forEach { pinResult ->
            pins.add(WPSPin(
                mode = 0,
                name = pinResult.algorithm,
                pin = pinResult.pin,
                sugg = false,
                score = 0.0,
                additionalData = mapOf("mode" to pinResult.mode),
                isFrom3WiFi = false,
                isExperimental = pinResult.isExperimental
            ))
        }

        val dbPins = searchWpsPinsInDatabases(network.BSSID)
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

    private suspend fun searchWpsPinsInDatabases(bssid: String): List<WPSPin> = withContext(Dispatchers.IO) {
        val pins = mutableListOf<WPSPin>()

        try {
            val localHelper = LocalAppDbHelper(requireContext())
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
                        pins.add(WPSPin(
                            mode = 0,
                            name = getString(R.string.source_local_database),
                            pin = network.wpsCode,
                            sugg = true,
                            score = 1.0,
                            isFrom3WiFi = true,
                            additionalData = mapOf(
                                "source" to "local_database",
                                "exact_match" to (format.equals(bssid, ignoreCase = true))
                            )
                        ))
                    }
                }
            }

            val databases = dbSetupViewModel.dbList.value?.filter {
                it.dbType == DbType.SQLITE_FILE_3WIFI || it.dbType == DbType.SQLITE_FILE_CUSTOM
            } ?: emptyList()

            databases.forEach { dbItem ->
                try {
                    when (dbItem.dbType) {
                        DbType.SQLITE_FILE_3WIFI -> {
                            val helper = SQLite3WiFiHelper(requireContext(), dbItem.path.toUri(), dbItem.directPath)
                            val searchFormats = MacAddressUtils.generateAllFormats(bssid)
                            val decimalBssids = searchFormats.mapNotNull { format ->
                                MacAddressUtils.convertToDecimal(format)?.toString()
                            }.distinct()

                            if (decimalBssids.isNotEmpty()) {
                                val results = helper.searchNetworksByBSSIDsAsync(decimalBssids)

                                results.forEach { result ->
                                    val wpsPin = result["WPSPIN"]?.toString()
                                    if (!wpsPin.isNullOrEmpty() && wpsPin != "0" && isValidWpsPin(wpsPin)) {
                                        pins.add(WPSPin(
                                            mode = 0,
                                            name = getString(R.string.from_database),
                                            pin = wpsPin,
                                            sugg = true,
                                            score = 1.0,
                                            isFrom3WiFi = true,
                                            additionalData = mapOf(
                                                "source" to "3wifi_database",
                                                "database" to dbItem.type,
                                                "exact_match" to true
                                            )
                                        ))
                                    }
                                }
                            }
                            helper.close()
                        }
                        DbType.SQLITE_FILE_CUSTOM -> {
                            val helper = SQLiteCustomHelper(requireContext(), dbItem.path.toUri(), dbItem.directPath)
                            val tableName = dbItem.tableName ?: return@forEach
                            val columnMap = dbItem.columnMap ?: return@forEach

                            val searchFormats = MacAddressUtils.generateAllFormats(bssid)
                            val results = helper.searchNetworksByBSSIDs(tableName, columnMap, searchFormats)

                            searchFormats.forEach { searchFormat ->
                                results[searchFormat]?.let { result ->
                                    val wpsPinColumn = columnMap["wps_pin"]
                                    if (wpsPinColumn != null) {
                                        val wpsPin = result[wpsPinColumn]?.toString()
                                        if (!wpsPin.isNullOrEmpty() && wpsPin != "0" && isValidWpsPin(wpsPin)) {
                                            pins.add(WPSPin(
                                                mode = 0,
                                                name = getString(R.string.source_custom_database),
                                                pin = wpsPin,
                                                sugg = true,
                                                score = 1.0,
                                                isFrom3WiFi = true,
                                                additionalData = mapOf(
                                                    "source" to "custom_database",
                                                    "database" to dbItem.type,
                                                    "exact_match" to true
                                                )
                                            ))
                                        }
                                    }
                                }
                            }
                            helper.close()
                        }
                        else -> {}
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WiFiScannerFragment", "Error searching database", e)
                }
            }

            val searchNeighborPins = searchNeighborWpsPins(bssid, 1000)
            pins.addAll(searchNeighborPins)

        } catch (e: Exception) {
            android.util.Log.e("WiFiScannerFragment", "Error searching WPS pins in databases", e)
        }

        pins.distinctBy { it.pin }
    }

    private suspend fun searchNeighborWpsPins(bssid: String, maxDistance: Int): List<WPSPin> = withContext(Dispatchers.IO) {
        val pins = mutableListOf<WPSPin>()
        val databases = dbSetupViewModel.dbList.value?.filter {
            it.dbType == DbType.SQLITE_FILE_3WIFI
        } ?: emptyList()

        val targetDecimal = MacAddressUtils.convertToDecimal(bssid)
        if (targetDecimal == null) {
            return@withContext pins
        }

        databases.forEach { dbItem ->
            try {
                val helper = SQLite3WiFiHelper(requireContext(), dbItem.path.toUri(), dbItem.directPath)
                val targetNic = targetDecimal and 0xFFFFFF
                val ouiBase = targetDecimal and 0xFFFFFF000000L

                val rangeStart = kotlin.math.max(0, targetNic - maxDistance) or ouiBase
                val rangeEnd = kotlin.math.min(0xFFFFFF, targetNic + maxDistance) or ouiBase

                val tableName = if (helper.getTableNames().contains("nets")) "nets" else "base"
                val query = """
                    SELECT BSSID, WPSPIN 
                    FROM $tableName 
                    WHERE BSSID BETWEEN ? AND ? 
                    AND BSSID != ?
                    AND WPSPIN IS NOT NULL 
                    AND WPSPIN != '0' 
                    AND WPSPIN != '1'
                    ORDER BY ABS(BSSID - ?) 
                    LIMIT 50
                """.trimIndent()

                helper.database?.rawQuery(query, arrayOf(
                    rangeStart.toString(),
                    rangeEnd.toString(),
                    targetDecimal.toString(),
                    targetDecimal.toString()
                ))?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val neighborDecimal = cursor.getLong(0)
                        val wpsPin = cursor.getString(1)

                        if (isValidWpsPin(wpsPin)) {
                            val distance = kotlin.math.abs((targetNic - (neighborDecimal and 0xFFFFFF)).toInt())
                            val (neighborType, isSuggested) = when (distance) {
                                in 1..10 -> Pair(getString(R.string.very_close_neighbor), true)
                                in 11..100 -> Pair(getString(R.string.close_neighbor), true)
                                else -> Pair(getString(R.string.medium_neighbor), false)
                            }

                            pins.add(WPSPin(
                                mode = 0,
                                name = neighborType,
                                pin = wpsPin,
                                sugg = isSuggested,
                                score = 1.0 / kotlin.math.sqrt(distance.toDouble() + 1.0),
                                isFrom3WiFi = true,
                                additionalData = mapOf(
                                    "source" to "neighbor_search",
                                    "distance" to distance.toString()
                                )
                            ))
                        }
                    }
                }
                helper.close()
            } catch (e: Exception) {
                android.util.Log.e("WiFiScannerFragment", "Error searching neighbor pins", e)
            }
        }
        pins.sortedByDescending { it.score }
    }

    private fun isValidWpsPin(pin: String): Boolean {
        return pin.matches("^\\d{8}$".toRegex())
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
        wifiAdapter = WifiAdapter(emptyList(), requireContext())

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

        binding.recyclerViewWifi.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = wifiAdapter
        }

        wifiAdapter.setOnItemClickListener { view, scanResult ->
            selectedWifi = scanResult
            showCustomContextMenu()
        }

        binding.buttonScanWifi.setOnClickListener {
            binding.buttonOnlineDb.visibility = View.VISIBLE
            binding.buttonOfflineDb.visibility = View.VISIBLE
            startWifiScan()
        }

        binding.buttonGenerateWpa.setOnClickListener {
            if (viewModel.isChecking.value != true) {
                showProgressBar()
                generateWpaAlgorithms()
            } else {
                Toast.makeText(context, getString(R.string.database_check_in_progress), Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonGenerateWps.setOnClickListener {
            if (viewModel.isChecking.value != true) {
                showProgressBar()
                generateWpsAlgorithms()
            } else {
                Toast.makeText(context, getString(R.string.database_check_in_progress), Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonOnlineDb.setOnClickListener {
            if (viewModel.isChecking.value != true) {
                val databases = dbSetupViewModel.dbList.value?.filter {
                    it.dbType == DbType.WIFI_API || it.dbType == DbType.LOCAL_APP_DB
                } ?: emptyList()

                if (databases.isEmpty()) {
                    Toast.makeText(context, getString(R.string.no_databases_configured), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                showProgressBar()
                viewModel.checkNetworksInDatabases(wifiAdapter.getWifiList(), databases)
            } else {
                Toast.makeText(context, getString(R.string.database_check_in_progress), Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonOfflineDb.setOnClickListener {
            if (viewModel.isChecking.value != true) {
                val databases = dbSetupViewModel.dbList.value?.filter {
                    it.dbType == DbType.SQLITE_FILE_3WIFI ||
                            it.dbType == DbType.SQLITE_FILE_CUSTOM ||
                            it.dbType == DbType.LOCAL_APP_DB
                } ?: emptyList()

                if (databases.isEmpty()) {
                    Toast.makeText(context, getString(R.string.no_databases_configured), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                showProgressBar()
                viewModel.checkNetworksInDatabases(wifiAdapter.getWifiList(), databases)
            } else {
                Toast.makeText(context, getString(R.string.database_check_in_progress), Toast.LENGTH_SHORT).show()
            }
        }

        binding.swipeRefreshLayout.setOnRefreshListener { startWifiScan() }
    }

    private fun observeViewModel() {
        viewModel.wifiList.observe(viewLifecycleOwner) { wifiList ->
            wifiAdapter.updateData(wifiList)
            binding.swipeRefreshLayout.isRefreshing = false
        }

        viewModel.scanState.observe(viewLifecycleOwner) { message ->
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            binding.swipeRefreshLayout.isRefreshing = false
        }

        viewModel.databaseResults.observe(viewLifecycleOwner) { results ->
            wifiAdapter.updateDatabaseResults(results)
            hideProgressBar()
        }

        viewModel.isChecking.observe(viewLifecycleOwner) { isChecking ->
            if (isChecking) {
                showProgressBar()
            } else {
                hideProgressBar()
            }
        }
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
        lifecycleScope.launch {
            try {
                Log.d("WiFiScannerFragment", "Starting update check...")
                val updateChecker = UpdateChecker(requireContext())
                updateChecker.checkForUpdates().collect { status ->
                    if (!isAdded) return@collect

                    Log.d("WiFiScannerFragment", "Update status received:")
                    Log.d("WiFiScannerFragment", "- App update: ${status.appUpdate}")
                    Log.d("WiFiScannerFragment", "- File updates: ${status.fileUpdates.size}")
                    Log.d("WiFiScannerFragment", "- DB updates: ${status.dbUpdates.size}")

                    val hasAppUpdate = status.appUpdate != null &&
                            status.appUpdate.currentVersion != status.appUpdate.newVersion
                    val hasSystemUpdates = status.fileUpdates.any { it.needsUpdate }
                    val hasDbUpdates = status.dbUpdates.any { it.needsUpdate }

                    Log.d("WiFiScannerFragment", "Processed status:")
                    Log.d("WiFiScannerFragment", "- hasAppUpdate: $hasAppUpdate")
                    Log.d("WiFiScannerFragment", "- hasSystemUpdates: $hasSystemUpdates")
                    Log.d("WiFiScannerFragment", "- hasDbUpdates: $hasDbUpdates")

                    if (!hasAppUpdate && !hasSystemUpdates && !hasDbUpdates) {
                        Log.d("WiFiScannerFragment", "No updates available, hiding banner")
                        binding.updateBanner.root.visibility = View.GONE
                        return@collect
                    }

                    Log.d("WiFiScannerFragment", "Updates available, showing banner")
                    binding.updateBanner.root.visibility = View.VISIBLE

                    val message = when {
                        hasAppUpdate && hasSystemUpdates && hasDbUpdates ->
                            getString(R.string.update_banner_all_updates)
                        hasAppUpdate && hasSystemUpdates ->
                            getString(R.string.update_banner_multiple)
                        hasAppUpdate && hasDbUpdates -> {
                            val newVersion = status.appUpdate?.newVersion ?: ""
                            getString(R.string.update_banner_app_and_smartlink, newVersion)
                        }
                        hasSystemUpdates && hasDbUpdates ->
                            getString(R.string.update_banner_system_and_smartlink)
                        hasAppUpdate -> {
                            val newVersion = status.appUpdate?.newVersion ?: ""
                            getString(R.string.update_banner_app, newVersion)
                        }
                        hasSystemUpdates ->
                            getString(R.string.update_banner_system)
                        hasDbUpdates ->
                            getString(R.string.update_banner_smartlink_db)
                        else ->
                            getString(R.string.update_banner_multiple)
                    }

                    Log.d("WiFiScannerFragment", "Banner message: $message")
                    binding.updateBanner.updateMessage.text = message

                    binding.updateBanner.buttonUpdate.setOnClickListener {
                        findNavController().navigate(R.id.nav_updates)
                    }

                    binding.updateBanner.buttonChangelog.visibility = if (hasAppUpdate) {
                        View.VISIBLE.also {
                            binding.updateBanner.buttonChangelog.setOnClickListener {
                                lifecycleScope.launch {
                                    try {
                                        status.appUpdate?.let { appUpdate ->
                                            updateChecker.getChangelog(appUpdate).collect { changelog ->
                                                showChangelogDialog(changelog)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(
                                            context,
                                            getString(R.string.error_fetching_changelog, e.message),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    } else {
                        View.GONE
                    }

                    binding.updateBanner.buttonClose.setOnClickListener {
                        binding.updateBanner.root.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e("WiFiScannerFragment", "Error checking for updates", e)
                binding.updateBanner.root.visibility = View.GONE
            }
        }
    }

    private fun showChangelogDialog(changelog: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.changelog)
            .setMessage(changelog)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun startWifiScan() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    viewModel.clearResults()
                    wifiAdapter.clearDatabaseResults()
                    startWifiScanInternal()
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
                    startWifiScanInternal()
                    hasScanned = true
                }
            }
            else -> {
                viewModel.clearResults()
                wifiAdapter.clearDatabaseResults()
                startWifiScanInternal()
                hasScanned = true
            }
        }
    }

    private fun startWifiScanInternal() {
        wifiAdapter.clearDatabaseResults()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModel.startWifiScan()
        } else {
            viewModel.startLegacyWifiScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                wifiAdapter.clearDatabaseResults()
                startWifiScanInternal()
                hasScanned = true
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.location_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showProgressBar() {
        if (_binding == null) return
        binding.progressBarDatabaseCheck.startAnimation()
    }

    private fun hideProgressBar() {
        if (_binding == null) return
        binding.progressBarDatabaseCheck.stopAnimation()
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
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_context_menu, null)
        val dialog = AlertDialog.Builder(requireContext())
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

        dialogView.findViewById<TextView>(R.id.action_copy_ssid)?.apply {
            tintDrawableStart(R.drawable.ic_content_copy)
            setOnClickListener {
                copyToClipboard(getString(R.string.ssid), selectedWifi?.SSID)
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_copy_bssid)?.apply {
            tintDrawableStart(R.drawable.ic_content_copy)
            setOnClickListener {
                copyToClipboard(getString(R.string.bssid), selectedWifi?.BSSID)
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_check_vendor)?.apply {
            tintDrawableStart(R.drawable.ic_info)
            setOnClickListener {
                selectedWifi?.BSSID?.let { showVendorDialog(it.uppercase(Locale.getDefault())) }
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_calculate_distance)?.apply {
            tintDrawableStart(R.drawable.ic_analytics)
            setOnClickListener {
                selectedWifi?.let { showDistanceDialog(it) }
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_connect_wps)?.apply {
            tintDrawableStart(R.drawable.ic_wifi)
            setOnClickListener {
                selectedWifi?.let { connectUsingWPSButton(it) }
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_connect_wps_pin)?.apply {
            tintDrawableStart(R.drawable.ic_lock_outline)
            setOnClickListener {
                selectedWifi?.let { wifi ->
                    showWpsPinDialog(wifi)
                    dialog.dismiss()
                } ?: Toast.makeText(
                    requireContext(),
                    "Wi-Fi network is not selected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_connect_wps_root)?.apply {
            val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
            val isRootEnabled = prefs.getBoolean("enable_root", false)
            visibility = if (isRootEnabled) View.VISIBLE else View.GONE

            tintDrawableStart(R.drawable.ic_lock)
            setOnClickListener {
                selectedWifi?.let { wifi ->
                    connectUsingWpsRoot(wifi)
                    dialog.dismiss()
                } ?: Toast.makeText(
                    requireContext(),
                    "Wi-Fi network is not selected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_generate_wps)?.apply {
            tintDrawableStart(R.drawable.ic_key)
            setOnClickListener {
                val intent = Intent(requireContext(), WpsGeneratorActivity::class.java).apply {
                    putExtra("BSSID", selectedWifi?.BSSID)
                }
                Log.d("WiFiScannerFragment", "Selected BSSID: ${selectedWifi?.BSSID}")
                startActivity(intent)
                dialog.dismiss()
            }
        }

        dialogView.findViewById<TextView>(R.id.action_generate_qr)?.apply {
            tintDrawableStart(R.drawable.ic_qr_code)
            setOnClickListener {
                selectedWifi?.let { wifi ->
                    val databaseResults = viewModel.databaseResults.value?.get(wifi.BSSID.lowercase(Locale.ROOT))

                    val password = databaseResults?.firstNotNullOfOrNull { result ->
                        result.databaseInfo["WiFiKey"] as? String
                            ?: result.databaseInfo["key"] as? String
                    }?.takeIf { it.isNotBlank() }

                    val finalPassword = password ?: ""
                    val security = if (finalPassword.isNotEmpty()) {
                        QrNavigationHelper.determineSecurityType(wifi.capabilities)
                    } else {
                        "NONE"
                    }

                    QrNavigationHelper.navigateToQrGenerator(
                        this@WiFiScannerFragment,
                        wifi.SSID,
                        finalPassword,
                        security
                    )
                }
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun connectUsingWpsRoot(network: ScanResult) {
        val wpsHelper = WpsRootConnectHelper(
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
            })

        if (!wpsHelper.checkRootAccess()) {
            Toast.makeText(requireContext(), getString(R.string.wps_root_no_root), Toast.LENGTH_SHORT).show()
            return
        }

        val databaseResults = viewModel.databaseResults.value?.get(network.BSSID.lowercase(Locale.ROOT))
        val wpsPin = databaseResults?.firstNotNullOfOrNull { result ->
            result.databaseInfo["WPSPIN"]?.toString()
                ?: result.databaseInfo["wps_pin"]?.toString()
                ?: result.databaseInfo["wps"]?.toString()
        }?.takeIf { it.isNotBlank() && it != "0" }

        if (wpsPin != null) {
            wpsHelper.connectToNetworkWps(network, wpsPin)
        } else {
            wpsHelper.connectToNetworkWps(network)
        }
    }

    private fun showWpsPinDialog(scanResult: ScanResult) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_wps_pin, null)
        val wpsPinEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextWpsPin)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connect_wps_pin_button)
            .setView(dialogView)
            .setPositiveButton(R.string.connect) { _, _ ->
                val wpsPin = wpsPinEditText.text.toString()
                if (wpsPin.isNotBlank()) {
                    connectUsingWPSPin(scanResult, wpsPin)
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.enter_wps_pin,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()
    }

    private fun connectUsingWPSPin(scanResult: ScanResult, pin: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.CHANGE_WIFI_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val wpsConfig = WpsInfo().apply {
                    setup = WpsInfo.KEYPAD
                    this.pin = pin
                    BSSID = scanResult.BSSID
                }

                val wifiManager =
                    requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.startWps(wpsConfig, object : WifiManager.WpsCallback() {
                    override fun onStarted(pin: String?) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.wps_started),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onSucceeded() {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.wps_succeeded),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onFailed(reason: Int) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.wps_failed, reason),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.change_wifi_state_permission_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.wps_not_supported),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun connectUsingWPSButton(scanResult: ScanResult) {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CHANGE_WIFI_STATE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val wpsConfig = WpsInfo().apply {
                setup = WpsInfo.PBC
                BSSID = scanResult.BSSID
            }

            val wifiManager =
                requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.startWps(wpsConfig, object : WifiManager.WpsCallback() {
                override fun onStarted(pin: String?) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.wps_started),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onSucceeded() {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.wps_succeeded),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onFailed(reason: Int) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.wps_failed, reason),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.change_wifi_state_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun copyToClipboard(label: String, text: String?) {
        val clipboard =
            requireContext().applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
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
        val localVendor2TextView: TextView =
            dialogView.findViewById(R.id.localVendorSource2TextView)
        val onlineVendor1TextView: TextView =
            dialogView.findViewById(R.id.onlineVendorSource1TextView)
        val onlineVendor2TextView: TextView =
            dialogView.findViewById(R.id.onlineVendorSource2TextView)

        bssidTextView.text = bssid

        val dialog = AlertDialog.Builder(requireContext())
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

        val formattedBSSID = bssid.replace(":", "").substring(0, 6)

        lifecycleScope.launch {
            updateVendorInfo(localVendor1TextView) {
                VendorChecker.checkVendorLocalSource1(
                    requireContext(),
                    formattedBSSID
                )
            }
            updateVendorInfo(localVendor2TextView) {
                VendorChecker.checkVendorLocalSource2(
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
            localVendor2TextView,
            onlineVendor1TextView,
            onlineVendor2TextView
        ).forEach { textView ->
            textView.setOnClickListener { copyToClipboard("Vendor", textView.text.toString()) }
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

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton(getString(R.string.close), null)
            .create()

        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        if (hasScanned) {
            viewModel.refreshWifiList()
        }

        viewModel.searchByMac.observe(viewLifecycleOwner) { searchByMac ->
            isSearchByMac = searchByMac
            if (searchByMac) {
                binding.searchTypeToggle.check(R.id.button_search_mac)
            } else {
                binding.searchTypeToggle.check(R.id.button_search_ssid)
            }
        }
    }

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 1
        private const val NOTIFICATION_URL = "https://raw.githubusercontent.com/LowSkillDeveloper/WIFI-Frankenstein/refs/heads/service/notification.json"
    }
}
