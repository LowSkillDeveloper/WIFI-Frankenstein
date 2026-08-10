package com.lsd.wififrankenstein.ui.wpsgenerator

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.DialogAdvancedSettingsBinding
import com.lsd.wififrankenstein.databinding.FragmentWpsGeneratorBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.util.MacAddressUtils
import com.lsd.wififrankenstein.util.PixiePinProvider
import com.lsd.wififrankenstein.util.SslHelper
import com.lsd.wififrankenstein.util.WpsPinGenerator
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WpsGeneratorFragment : Fragment() {

    private var _binding: FragmentWpsGeneratorBinding? = null
    private val binding get() = _binding!!

    private lateinit var wpsGeneratorAdapter: WpsGeneratorAdapter
    private lateinit var wpsPinGenerator: WpsPinGenerator
    private val dbSetupViewModel: DbSetupViewModel by lazy {
        DbSetupViewModel.getInstance(requireActivity().application)
    }

    private var scannedNetworks: List<ScanResult> = emptyList()
    private var currentMode = MODE_SCAN

    companion object {
        private const val MODE_SCAN = 0
        private const val MODE_MANUAL = 1
        private const val PREFS_NAME = "wps_generator_settings"
        private const val KEY_EXPERIMENTAL = "experimental"
        private const val KEY_SEARCH_DB = "search_db"
        private const val KEY_IN_APP = "in_app"
        private const val KEY_OFFLINE = "offline"
        private const val KEY_ONLINE = "online"
        private const val KEY_LOCAL = "local"
        private const val KEY_NEIGHBORS = "neighbors"
        private const val KEY_NEIGHBOR_DISTANCE = "neighbor_distance"
        private val WPS_PIN_REGEX = Regex("^\\d{8}$")
    }

    private lateinit var prefs: SharedPreferences
    private var settings = WpsGeneratorSettings()

    data class WpsGeneratorSettings(
        val includeExperimental: Boolean = true,
        val searchDatabases: Boolean = true,
        val includeInApp: Boolean = true,
        val includeOffline: Boolean = true,
        val includeOnline: Boolean = false,
        val includeLocal: Boolean = true,
        val includeNeighbors: Boolean = true,
        val neighborDistance: Int = 1000
    )

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
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWpsGeneratorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        wpsPinGenerator = WpsPinGenerator()
        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        wpsGeneratorAdapter = WpsGeneratorAdapter()

        binding.recyclerViewResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = wpsGeneratorAdapter
        }

        setupTabs()
        setupOptions()
        setupListeners()

        showScanMode()
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.scan_networks))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.manual_input))

        binding.tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showScanMode()
                    1 -> showManualInputMode()
                }
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun setupOptions() {
        applySettingsToGenerator()
    }

    private fun applySettingsToGenerator() {
        settings = WpsGeneratorSettings(
            includeExperimental = prefs.getBoolean(KEY_EXPERIMENTAL, true),
            searchDatabases = prefs.getBoolean(KEY_SEARCH_DB, true),
            includeInApp = prefs.getBoolean(KEY_IN_APP, true),
            includeOffline = prefs.getBoolean(KEY_OFFLINE, true),
            includeOnline = prefs.getBoolean(KEY_ONLINE, false),
            includeLocal = prefs.getBoolean(KEY_LOCAL, true),
            includeNeighbors = prefs.getBoolean(KEY_NEIGHBORS, true),
            neighborDistance = prefs.getInt(KEY_NEIGHBOR_DISTANCE, 1000)
        )
    }

    private fun setupListeners() {
        binding.generateButton.setOnClickListener {
            if (currentMode == MODE_MANUAL) {
                generateForSingleBssid(binding.editTextBssid.text.toString().trim())
            } else {
                checkLocationPermissionAndScan()
            }
        }

        binding.buttonAdvancedSettings.setOnClickListener {
            showAdvancedSettingsDialog()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            dbSetupViewModel.loadDbList()
        }
    }

    private fun showAdvancedSettingsDialog() {
        val dialogBinding = DialogAdvancedSettingsBinding.inflate(layoutInflater)

        dialogBinding.dialogSwitchExperimental.isChecked = settings.includeExperimental
        dialogBinding.dialogSwitchSearchDatabases.isChecked = settings.searchDatabases
        dialogBinding.dialogSwitchIncludeInApp.isChecked = settings.includeInApp
        dialogBinding.dialogSwitchIncludeOffline.isChecked = settings.includeOffline
        dialogBinding.dialogSwitchIncludeOnline.isChecked = settings.includeOnline
        dialogBinding.dialogSwitchIncludeLocal.isChecked = settings.includeLocal
        dialogBinding.dialogSwitchIncludeNeighbors.isChecked = settings.includeNeighbors

        when (settings.neighborDistance) {
            10 -> dialogBinding.dialogRadioNeighborClose.isChecked = true
            100 -> dialogBinding.dialogRadioNeighborMedium.isChecked = true
            else -> dialogBinding.dialogRadioNeighborFar.isChecked = true
        }

        dialogBinding.dialogSwitchSearchDatabases.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.dialogLayoutDatabaseOptions.visibility =
                if (isChecked) View.VISIBLE else View.GONE
        }

        dialogBinding.dialogSwitchIncludeNeighbors.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.dialogRadioGroupNeighborDistance.visibility =
                if (isChecked) View.VISIBLE else View.GONE
        }

        dialogBinding.dialogLayoutDatabaseOptions.visibility =
            if (settings.searchDatabases) View.VISIBLE else View.GONE
        dialogBinding.dialogRadioGroupNeighborDistance.visibility =
            if (settings.includeNeighbors) View.VISIBLE else View.GONE

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.dialogButtonSave.setOnClickListener {
            settings = WpsGeneratorSettings(
                includeExperimental = dialogBinding.dialogSwitchExperimental.isChecked,
                searchDatabases = dialogBinding.dialogSwitchSearchDatabases.isChecked,
                includeInApp = dialogBinding.dialogSwitchIncludeInApp.isChecked,
                includeOffline = dialogBinding.dialogSwitchIncludeOffline.isChecked,
                includeOnline = dialogBinding.dialogSwitchIncludeOnline.isChecked,
                includeLocal = dialogBinding.dialogSwitchIncludeLocal.isChecked,
                includeNeighbors = dialogBinding.dialogSwitchIncludeNeighbors.isChecked,
                neighborDistance = when {
                    dialogBinding.dialogRadioNeighborClose.isChecked -> 10
                    dialogBinding.dialogRadioNeighborMedium.isChecked -> 100
                    else -> 1000
                }
            )

            with(prefs.edit()) {
                putBoolean(KEY_EXPERIMENTAL, settings.includeExperimental)
                putBoolean(KEY_SEARCH_DB, settings.searchDatabases)
                putBoolean(KEY_IN_APP, settings.includeInApp)
                putBoolean(KEY_OFFLINE, settings.includeOffline)
                putBoolean(KEY_ONLINE, settings.includeOnline)
                putBoolean(KEY_LOCAL, settings.includeLocal)
                putBoolean(KEY_NEIGHBORS, settings.includeNeighbors)
                putInt(KEY_NEIGHBOR_DISTANCE, settings.neighborDistance)
                apply()
            }

            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showScanMode() {
        currentMode = MODE_SCAN
        binding.manualInputGroup.visibility = View.GONE
        binding.generateButton.text = getString(R.string.scan_wifi_networks)
        binding.generateButton.isEnabled = true
        binding.recyclerViewResults.visibility = View.GONE
        binding.statusText.text = getString(R.string.press_scan_to_start)
        binding.statusText.visibility = View.VISIBLE
    }

    private fun showManualInputMode() {
        currentMode = MODE_MANUAL
        binding.manualInputGroup.visibility = View.VISIBLE
        binding.generateButton.text = getString(R.string.generate_pins)
        binding.generateButton.isEnabled = true
        binding.recyclerViewResults.visibility = View.GONE
        binding.statusText.visibility = View.GONE
    }

    private fun checkLocationPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            startWifiScan()
        }
    }

    private fun startWifiScan() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.progressBar.startAnimation()
                binding.generateButton.isEnabled = false

                val wifiManager =
                    requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                withContext(Dispatchers.IO) {
                    wifiManager.startScan()
                }

                scannedNetworks = wifiManager.scanResults.sortedByDescending { it.level }

                binding.statusText.visibility = View.VISIBLE

                if (scannedNetworks.isNotEmpty()) {
                    binding.statusText.text =
                        getString(R.string.networks_found, scannedNetworks.size)
                    generateForAllNetworks()
                } else {
                    binding.statusText.text = getString(R.string.no_networks_found)
                }

            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_scanning_wifi),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                if (_binding != null) {
                    binding.progressBar.stopAnimation()
                    binding.generateButton.isEnabled = true
                }
            }
        }
    }

    private fun showNetworkSelectionDialog() {
        val networkNames = scannedNetworks.map { "${it.SSID} (${it.BSSID})" }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.select_network))
            .setItems(networkNames) { _, which ->
                val selectedNetwork = scannedNetworks[which]
                generateForSingleBssid(selectedNetwork.BSSID)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun generateForSingleBssid(bssid: String) {
        if (bssid.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.enter_valid_bssid),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressBar.startAnimation()

            val results = mutableListOf<WpsGeneratorResult>()
            val includeExperimental = settings.includeExperimental

            val suggestedPins = wpsPinGenerator.generateSuggestedPins(
                bssid,
                includeExperimental = includeExperimental
            )
            val allPins =
                wpsPinGenerator.generateAllPins(bssid, includeExperimental = includeExperimental)

            val network = scannedNetworks.find { it.BSSID.equals(bssid, ignoreCase = true) }
            val ssid = network?.SSID ?: getString(R.string.unknown_network)

            val wpsPins = mutableListOf<WPSPin>()

            suggestedPins.forEach { pinResult ->
                wpsPins.add(
                    createPin(
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
                wpsPins.add(
                    createPin(
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

            if (settings.searchDatabases) {
                val dbPins = searchInDatabases(bssid)
                wpsPins.addAll(dbPins)
            }

            val sortedPins = sortPinsByPriority(wpsPins)

            results.add(
                WpsGeneratorResult(
                    ssid = ssid,
                    bssid = bssid,
                    pins = sortedPins
                )
            )

            wpsGeneratorAdapter.submitList(results)
            binding.recyclerViewResults.visibility = View.VISIBLE
            binding.progressBar.stopAnimation()
        }
    }

    private fun generateForAllNetworks() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressBar.startAnimation()
            binding.generateButton.isEnabled = false

            val results = mutableListOf<WpsGeneratorResult>()
            val includeExperimental = settings.includeExperimental

            scannedNetworks.forEach { network ->
                val suggestedPins = wpsPinGenerator.generateSuggestedPins(
                    network.BSSID,
                    includeExperimental = includeExperimental
                )
                val allPins = wpsPinGenerator.generateAllPins(
                    network.BSSID,
                    includeExperimental = includeExperimental
                )

                val wpsPins = mutableListOf<WPSPin>()

                suggestedPins.forEach { pinResult ->
                    wpsPins.add(
                        createPin(
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
                    wpsPins.add(
                        createPin(
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

                if (settings.searchDatabases) {
                    val dbPins = searchInDatabases(network.BSSID)
                    wpsPins.addAll(dbPins)
                }

                if (wpsPins.isNotEmpty()) {
                    val sortedPins = sortPinsByPriority(wpsPins)

                    results.add(
                        WpsGeneratorResult(
                            ssid = network.SSID,
                            bssid = network.BSSID,
                            pins = sortedPins
                        )
                    )
                }
            }

            val sortedResults = results.sortedWith(
                compareBy<WpsGeneratorResult> { result ->
                    when {
                        result.pins.any { it.sugg } -> 0
                        hasPossiblePins(result) -> 1
                        else -> 2
                    }
                }.thenBy { it.ssid }
            )

            wpsGeneratorAdapter.submitList(sortedResults)
            binding.recyclerViewResults.visibility = View.VISIBLE
            binding.progressBar.stopAnimation()
            binding.generateButton.isEnabled = true

            if (results.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.no_pins_generated),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val networksWithSuggested = results.count { it.pins.any { pin -> pin.sugg } }
                val networksWithPossible = results.count { hasPossiblePins(it) }

                when {
                    networksWithSuggested > 0 && networksWithPossible > 0 -> {
                        Toast.makeText(
                            requireContext(),
                            getString(
                                R.string.pins_generated_with_suggested_and_possible,
                                results.size,
                                networksWithSuggested,
                                networksWithPossible
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    networksWithSuggested > 0 -> {
                        Toast.makeText(
                            requireContext(),
                            getString(
                                R.string.pins_generated_for_networks_with_suggested,
                                results.size,
                                networksWithSuggested
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    networksWithPossible > 0 -> {
                        Toast.makeText(
                            requireContext(),
                            getString(
                                R.string.pins_generated_with_possible,
                                results.size,
                                networksWithPossible
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    else -> {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.pins_generated_for_networks, results.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            scannedNetworks = emptyList()
            binding.generateButton.text = getString(R.string.scan_wifi_networks)
        }
    }

    private suspend fun searchInDatabases(bssid: String): List<WPSPin> = coroutineScope {
        val pins = mutableListOf<WPSPin>()

        try {
            val jobs = mutableListOf<Deferred<List<WPSPin>>>()

            if (settings.includeInApp) {
                jobs += async { searchInAppDatabase(bssid) }
            }

            if (settings.includeOffline) {
                jobs += async { searchOfflineDatabases(bssid) }
            }

            if (settings.includeOnline) {
                jobs += async { searchOnlineDatabases(bssid) }
            }

            if (settings.includeLocal) {
                jobs += async { searchLocalDatabase(bssid) }
            }

            if (settings.includeNeighbors) {
                jobs += async { searchNeighborPins(bssid, settings.neighborDistance) }
            }

            for (job in jobs) {
                pins.addAll(job.await())
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_searching_databases),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        pins.distinctBy { it.pin }
    }

    private suspend fun searchOfflineDatabases(bssid: String): List<WPSPin> =
        withContext(Dispatchers.IO) {
            val pins = mutableListOf<WPSPin>()
            val dbItems = dbSetupViewModel.dbList.value?.filter {
                it.dbType == DbType.SQLITE_FILE_P3WIFI || it.dbType == DbType.SQLITE_FILE_CUSTOM ||
                        it.dbType == DbType.SMARTLINK_SQLITE_FILE_P3WIFI || it.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM
            } ?: emptyList()

            PixiePinProvider.find3WiFiPins(requireContext(), bssid, dbItems).forEach { scored ->
                pins.add(
                    createPin(
                        mode = 0,
                        name = getString(R.string.from_database),
                        pin = scored.pin,
                        sugg = true,
                        score = 1.0,
                        additionalData = mapOf(
                            "source" to scored.source,
                            "exact_match" to true
                        ),
                        isFrom3WiFi = true,
                        isExperimental = false
                    )
                )
            }

            PixiePinProvider.findCustomPins(requireContext(), bssid, dbItems).forEach { scored ->
                pins.add(
                    createPin(
                        mode = 0,
                        name = getString(R.string.source_custom_database),
                        pin = scored.pin,
                        sugg = true,
                        score = 1.0,
                        additionalData = mapOf(
                            "source" to scored.source,
                            "exact_match" to true
                        ),
                        isFrom3WiFi = true,
                        isExperimental = false
                    )
                )
            }

            pins.distinctBy { it.pin }
        }

    private suspend fun searchOnlineDatabases(bssid: String): List<WPSPin> =
        withContext(Dispatchers.IO) {
            val pins = mutableListOf<WPSPin>()
            val databases = dbSetupViewModel.getWifiApiDatabases()

            databases.forEach { db ->
                try {
                    val url =
                        URL("${db.path}/api/apiwps?key=${db.apiKey}&bssid=${bssid.uppercase()}")
                    val connection = url.openConnection() as HttpURLConnection
                    SslHelper.configure(connection)
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 10000

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonResponse = JSONObject(response)

                        if (jsonResponse.getBoolean("result")) {
                            val data = jsonResponse.optJSONObject("data")
                            if (data != null && data.has(bssid.uppercase())) {
                                val bssidData = data.getJSONObject(bssid.uppercase())
                                val scores = bssidData.optJSONArray("scores")

                                scores?.let {
                                    for (i in 0 until it.length()) {
                                        val score = it.getJSONObject(i)
                                        val name = score.optString("name", "Unknown")
                                        val value = score.optString("value", "")
                                        val scoreValue = score.optDouble("score", 0.0)

                                        if (isValidWpsPin(value)) {
                                            pins.add(
                                                createPin(
                                                    mode = 0,
                                                    name = name,
                                                    pin = value,
                                                    sugg = scoreValue > 0.8,
                                                    score = scoreValue,
                                                    additionalData = mapOf(
                                                        "source" to "online_api",
                                                        "api" to db.path,
                                                        "exact_match" to (scoreValue > 0.8)
                                                    ),
                                                    isFrom3WiFi = true,
                                                    isExperimental = false
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    com.lsd.wififrankenstein.util.Log.e(
                        "WpsGeneratorFragment",
                        "Error searching online database ${db.path}",
                        e
                    )
                }
            }
            pins
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

    private suspend fun searchLocalDatabase(bssid: String): List<WPSPin> =
        withContext(Dispatchers.IO) {
            val pins = mutableListOf<WPSPin>()
            try {
                val helper = LocalAppDbHelper(requireContext())
                val searchFormats = MacAddressUtils.generateAllFormats(bssid)

                searchFormats.forEach { format ->
                    val results = helper.searchRecordsWithFilters(
                        query = format,
                        filterByName = false,
                        filterByMac = true,
                        filterByPassword = false,
                        filterByWps = true
                    )

                    results.forEach { network ->
                        if (!network.wpsCode.isNullOrEmpty() && isValidWpsPin(network.wpsCode)) {
                            pins.add(
                                createPin(
                                    mode = 0,
                                    name = getString(R.string.source_local_database),
                                    pin = network.wpsCode,
                                    sugg = true,
                                    score = 1.0,
                                    additionalData = mapOf(
                                        "source" to "local_database",
                                        "exact_match" to (format.equals(bssid, ignoreCase = true))
                                    ),
                                    isFrom3WiFi = true,
                                    isExperimental = false
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                com.lsd.wififrankenstein.util.Log.e(
                    "WpsGeneratorFragment",
                    "Error searching local database",
                    e
                )
            }
            pins.distinctBy { it.pin }
        }

    private suspend fun searchNeighborPins(bssid: String, maxDistance: Int): List<WPSPin> =
        withContext(Dispatchers.IO) {
            val dbItems = dbSetupViewModel.dbList.value?.filter {
                it.dbType == DbType.SQLITE_FILE_P3WIFI || it.dbType == DbType.SMARTLINK_SQLITE_FILE_P3WIFI
            } ?: emptyList()

            PixiePinProvider.find3WiFiNeighborPins(requireContext(), bssid, dbItems).map { scored ->
                val distance = when (scored.score) {
                    85 -> kotlin.math.max(
                        1,
                        kotlin.math.min(100, 100 - (scored.score * 10).toInt())
                    )

                    else -> 500
                }
                val neighborType = when {
                    distance <= 10 -> getString(R.string.very_close_neighbor)
                    distance <= 100 -> getString(R.string.close_neighbor)
                    else -> getString(R.string.medium_neighbor)
                }
                createPin(
                    mode = 0,
                    name = neighborType,
                    pin = scored.pin,
                    sugg = scored.score >= 80,
                    score = scored.score / 100.0,
                    additionalData = mapOf(
                        "source" to "neighbor_search",
                        "distance" to distance.toString(),
                        "exact_match" to false
                    ),
                    isFrom3WiFi = true,
                    isExperimental = false
                )
            }.sortedByDescending { it.score }
        }

    private suspend fun searchInAppDatabase(bssid: String): List<WPSPin> =
        withContext(Dispatchers.IO) {
            val pins = mutableListOf<WPSPin>()
            try {
                val dbFile = getFileFromInternalStorageOrAssets("wps_pin.db")
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )

                val searchFormats = MacAddressUtils.generateAllFormats(bssid)
                val macPrefixes = searchFormats.mapNotNull { format ->
                    val hexString = MacAddressUtils.convertToHexString(format)
                    if (hexString != null && hexString.length >= 8) {
                        hexString.substring(0, 8)
                    } else null
                }.distinct()

                macPrefixes.forEach { macPrefix ->
                    val cursor = db.rawQuery("SELECT pin FROM pins WHERE mac=?", arrayOf(macPrefix))
                    cursor.use {
                        while (it.moveToNext()) {
                            val pin = it.getString(it.getColumnIndexOrThrow("pin"))
                            if (isValidWpsPin(pin)) {
                                pins.add(
                                    createPin(
                                        mode = 0,
                                        name = getString(R.string.source_inapp_database),
                                        pin = pin,
                                        sugg = false,
                                        score = 0.5,
                                        additionalData = mapOf(
                                            "source" to "inapp_database",
                                            "exact_match" to false
                                        ),
                                        isFrom3WiFi = false,
                                        isExperimental = false
                                    )
                                )
                            }
                        }
                    }
                }
                db.close()
            } catch (e: Exception) {
                com.lsd.wififrankenstein.util.Log.e(
                    "WpsGeneratorFragment",
                    "Error accessing in-app database",
                    e
                )
            }
            pins.distinctBy { it.pin }
        }

    private fun hasPossiblePins(result: WpsGeneratorResult): Boolean {
        return result.pins.any { !it.sugg && it.showQuestionMark }
    }

    private fun getFileFromInternalStorageOrAssets(fileName: String): java.io.File {
        val file = java.io.File(requireContext().filesDir, fileName)
        if (!file.exists()) {
            requireContext().assets.open(fileName).use { input ->
                java.io.FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file
    }


    private fun isValidWpsPin(pin: String): Boolean {
        return pin.isEmpty() || pin.matches(WPS_PIN_REGEX)
    }

    private fun decimalToBssid(decimal: Long): String {
        return MacAddressUtils.formatToColonSeparated(decimal.toString()) ?: String.format(
            "%012X",
            decimal
        )
            .chunked(2).joinToString(":")
    }

    private fun createPin(
        mode: Int,
        name: String,
        pin: String,
        sugg: Boolean,
        score: Double,
        additionalData: Map<String, Any?>,
        isFrom3WiFi: Boolean,
        isExperimental: Boolean
    ): WPSPin {
        val source = additionalData["source"] as? String
        val exactMatch = additionalData["exact_match"] as? Boolean ?: false
        val display = if (additionalData.containsKey("source")) {
            "${name} ($source)"
        } else name

        val showQ = when {
            isFrom3WiFi && exactMatch != true -> true
            source == "inapp_database" -> true
            source == "neighbor_search" && !sugg -> true
            else -> false
        }

        return WPSPin(
            mode,
            name,
            pin,
            sugg,
            score,
            additionalData,
            isFrom3WiFi,
            isExperimental,
            display,
            showQ
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
