package com.lsd.wififrankenstein.ui.wpsgenerator

import android.Manifest
import android.content.Context
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
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentWpsGeneratorBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SQLite3WiFiHelper
import com.lsd.wififrankenstein.ui.dbsetup.SQLiteCustomHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.util.MacAddressUtils
import com.lsd.wififrankenstein.util.WpsPinGenerator
import kotlinx.coroutines.Dispatchers
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
    private val dbSetupViewModel: DbSetupViewModel by activityViewModels()

    private var scannedNetworks: List<ScanResult> = emptyList()

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startWifiScan()
        } else {
            Toast.makeText(requireContext(), getString(R.string.location_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWpsGeneratorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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

        binding.radioGroupMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioManual -> {
                    binding.layoutManualInput.visibility = View.VISIBLE
                    binding.layoutScanOptions.visibility = View.GONE
                    binding.buttonScan.visibility = View.GONE
                    binding.textViewScanResult.visibility = View.GONE
                    binding.buttonGenerate.visibility = View.VISIBLE
                    binding.recyclerViewResults.visibility = View.GONE
                }
                R.id.radioScan -> {
                    binding.layoutManualInput.visibility = View.GONE
                    binding.layoutScanOptions.visibility = View.VISIBLE
                    binding.buttonScan.visibility = View.VISIBLE
                    binding.textViewScanResult.visibility = View.VISIBLE
                    binding.buttonGenerate.visibility = View.GONE
                    binding.recyclerViewResults.visibility = View.VISIBLE
                }
            }
        }

        binding.buttonGenerate.setOnClickListener {
            val bssid = binding.editTextBssid.text.toString().trim()
            if (bssid.isNotEmpty()) {
                generateForSingleBssid(bssid)
            } else {
                Toast.makeText(requireContext(), getString(R.string.enter_valid_bssid), Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonScan.setOnClickListener {
            checkLocationPermissionAndScan()
        }

        binding.buttonSelectNetwork.setOnClickListener {
            if (scannedNetworks.isNotEmpty()) {
                showNetworkSelectionDialog()
            } else {
                Toast.makeText(requireContext(), getString(R.string.scan_networks_first), Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonGenerateAll.setOnClickListener {
            generateForAllNetworks()
        }

        binding.switchIncludeExperimental.setOnCheckedChangeListener { _, _ ->
            val currentBssid = binding.editTextBssid.text.toString().trim()
            if (currentBssid.isNotEmpty() && binding.radioManual.isChecked) {
                generateForSingleBssid(currentBssid)
            }
        }

        binding.switchSearchDatabases.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutDatabaseOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.switchIncludeNeighbors.setOnCheckedChangeListener { _, isChecked ->
            binding.radioGroupNeighborDistance.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.radioManual.isChecked = true

        binding.switchIncludeInApp.isChecked = true
        binding.switchIncludeOffline.isChecked = true
        binding.switchIncludeOnline.isChecked = false
        binding.switchIncludeLocal.isChecked = true
        binding.switchIncludeNeighbors.isChecked = true
        binding.radioNeighborFar.isChecked = true
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            dbSetupViewModel.loadDbList()
        }
    }

    private fun checkLocationPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            startWifiScan()
        }
    }

    private fun startWifiScan() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                binding.buttonScan.isEnabled = false

                val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                withContext(Dispatchers.IO) {
                    wifiManager.startScan()
                }

                scannedNetworks = wifiManager.scanResults.sortedByDescending { it.level }

                if (scannedNetworks.isNotEmpty()) {
                    binding.buttonSelectNetwork.visibility = View.VISIBLE
                    binding.buttonGenerateAll.visibility = View.VISIBLE
                    binding.textViewScanResult.text = getString(R.string.networks_found, scannedNetworks.size)
                } else {
                    binding.textViewScanResult.text = getString(R.string.no_networks_found)
                }

            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.error_scanning_wifi), Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.buttonScan.isEnabled = true
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
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE

            val results = mutableListOf<WpsGeneratorResult>()
            val includeExperimental = binding.switchIncludeExperimental.isChecked

            val suggestedPins = wpsPinGenerator.generateSuggestedPins(bssid, includeExperimental = includeExperimental)
            val allPins = wpsPinGenerator.generateAllPins(bssid, includeExperimental = includeExperimental)

            val network = scannedNetworks.find { it.BSSID.equals(bssid, ignoreCase = true) }
            val ssid = network?.SSID ?: getString(R.string.unknown_network)

            val wpsPins = mutableListOf<WPSPin>()

            suggestedPins.forEach { pinResult ->
                wpsPins.add(WPSPin(
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
                wpsPins.add(WPSPin(
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

            if (binding.switchSearchDatabases.isChecked) {
                val dbPins = searchInDatabases(bssid)
                wpsPins.addAll(dbPins)
            }

            val sortedPins = sortPinsByPriority(wpsPins)

            results.add(WpsGeneratorResult(
                ssid = ssid,
                bssid = bssid,
                pins = sortedPins
            ))

            wpsGeneratorAdapter.submitList(results)
            binding.recyclerViewResults.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun generateForAllNetworks() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.buttonGenerateAll.isEnabled = false

            val results = mutableListOf<WpsGeneratorResult>()
            val includeExperimental = binding.switchIncludeExperimental.isChecked

            scannedNetworks.forEach { network ->
                val suggestedPins = wpsPinGenerator.generateSuggestedPins(network.BSSID, includeExperimental = includeExperimental)
                val allPins = wpsPinGenerator.generateAllPins(network.BSSID, includeExperimental = includeExperimental)

                val wpsPins = mutableListOf<WPSPin>()

                suggestedPins.forEach { pinResult ->
                    wpsPins.add(WPSPin(
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
                    wpsPins.add(WPSPin(
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

                if (binding.switchSearchDatabases.isChecked) {
                    val dbPins = searchInDatabases(network.BSSID)
                    wpsPins.addAll(dbPins)
                }

                if (wpsPins.isNotEmpty()) {
                    val sortedPins = sortPinsByPriority(wpsPins)

                    results.add(WpsGeneratorResult(
                        ssid = network.SSID,
                        bssid = network.BSSID,
                        pins = sortedPins
                    ))
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
            binding.progressBar.visibility = View.GONE
            binding.buttonGenerateAll.isEnabled = true

            if (results.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.no_pins_generated), Toast.LENGTH_SHORT).show()
            } else {
                val networksWithSuggested = results.count { it.pins.any { pin -> pin.sugg } }
                val networksWithPossible = results.count { hasPossiblePins(it) }

                when {
                    networksWithSuggested > 0 && networksWithPossible > 0 -> {
                        Toast.makeText(requireContext(),
                            getString(R.string.pins_generated_with_suggested_and_possible, results.size, networksWithSuggested, networksWithPossible),
                            Toast.LENGTH_SHORT).show()
                    }
                    networksWithSuggested > 0 -> {
                        Toast.makeText(requireContext(),
                            getString(R.string.pins_generated_for_networks_with_suggested, results.size, networksWithSuggested),
                            Toast.LENGTH_SHORT).show()
                    }
                    networksWithPossible > 0 -> {
                        Toast.makeText(requireContext(),
                            getString(R.string.pins_generated_with_possible, results.size, networksWithPossible),
                            Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        Toast.makeText(requireContext(),
                            getString(R.string.pins_generated_for_networks, results.size),
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private suspend fun searchInDatabases(bssid: String): List<WPSPin> = withContext(Dispatchers.IO) {
        val pins = mutableListOf<WPSPin>()

        try {
            if (binding.switchIncludeInApp.isChecked) {
                pins.addAll(searchInAppDatabase(bssid))
            }

            if (binding.switchIncludeOffline.isChecked) {
                pins.addAll(searchOfflineDatabases(bssid))
            }

            if (binding.switchIncludeOnline.isChecked) {
                pins.addAll(searchOnlineDatabases(bssid))
            }

            if (binding.switchIncludeLocal.isChecked) {
                pins.addAll(searchLocalDatabase(bssid))
            }

            if (binding.switchIncludeNeighbors.isChecked) {
                val neighborSearchLevel = when {
                    binding.radioNeighborClose.isChecked -> 10
                    binding.radioNeighborMedium.isChecked -> 100
                    binding.radioNeighborFar.isChecked -> 1000
                    else -> 100
                }
                pins.addAll(searchNeighborPins(bssid, neighborSearchLevel))
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), getString(R.string.error_searching_databases), Toast.LENGTH_SHORT).show()
            }
        }

        pins.distinctBy { it.pin }
    }

    private suspend fun searchOfflineDatabases(bssid: String): List<WPSPin> = withContext(Dispatchers.IO) {
        val pins = mutableListOf<WPSPin>()
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
                android.util.Log.e("WpsGeneratorFragment", "Error searching offline database", e)
            }
        }
        pins.distinctBy { it.pin }
    }

    private suspend fun searchOnlineDatabases(bssid: String): List<WPSPin> = withContext(Dispatchers.IO) {
        val pins = mutableListOf<WPSPin>()
        val databases = dbSetupViewModel.getWifiApiDatabases()

        databases.forEach { db ->
            try {
                val url = URL("${db.path}/api/apiwps?key=${db.apiKey}&bssid=${bssid.uppercase()}")
                val connection = url.openConnection() as HttpURLConnection
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
                                        pins.add(WPSPin(
                                            mode = 0,
                                            name = name,
                                            pin = value,
                                            sugg = scoreValue > 0.8,
                                            score = scoreValue,
                                            isFrom3WiFi = true,
                                            additionalData = mapOf(
                                                "source" to "online_api",
                                                "api" to db.path,
                                                "exact_match" to (scoreValue > 0.8)
                                            )
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WpsGeneratorFragment", "Error searching online database ${db.path}", e)
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

    private suspend fun searchLocalDatabase(bssid: String): List<WPSPin> = withContext(Dispatchers.IO) {
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
        } catch (e: Exception) {
            android.util.Log.e("WpsGeneratorFragment", "Error searching local database", e)
        }
        pins.distinctBy { it.pin }
    }

    private suspend fun searchNeighborPins(bssid: String, maxDistance: Int): List<WPSPin> = withContext(Dispatchers.IO) {
        val pins = mutableListOf<WPSPin>()
        val databases = dbSetupViewModel.dbList.value?.filter {
            it.dbType == DbType.SQLITE_FILE_3WIFI
        } ?: emptyList()

        val targetDecimal = MacAddressUtils.convertToDecimal(bssid)
        if (targetDecimal == null) {
            android.util.Log.e("WpsGeneratorFragment", "Could not convert BSSID to decimal: $bssid")
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
                                    "neighbor_bssid" to decimalToBssid(neighborDecimal),
                                    "distance" to distance.toString(),
                                    "exact_match" to false
                                )
                            ))
                        }
                    }
                }
                helper.close()
            } catch (e: Exception) {
                android.util.Log.e("WpsGeneratorFragment", "Error searching neighbor pins", e)
            }
        }
        pins.sortedByDescending { it.score }
    }

    private suspend fun searchInAppDatabase(bssid: String): List<WPSPin> = withContext(Dispatchers.IO) {
        val pins = mutableListOf<WPSPin>()
        try {
            val dbFile = getFileFromInternalStorageOrAssets("wps_pin.db")
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)

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
                            pins.add(WPSPin(
                                mode = 0,
                                name = getString(R.string.source_inapp_database),
                                pin = pin,
                                sugg = false,
                                score = 0.5,
                                isFrom3WiFi = false,
                                additionalData = mapOf(
                                    "source" to "inapp_database",
                                    "exact_match" to false
                                )
                            ))
                        }
                    }
                }
            }
            db.close()
        } catch (e: Exception) {
            android.util.Log.e("WpsGeneratorFragment", "Error accessing in-app database", e)
        }
        pins.distinctBy { it.pin }
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

    private fun hasPossiblePins(result: WpsGeneratorResult): Boolean {
        return result.pins.any { !it.sugg && shouldShowQuestionMark(it) }
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
        return pin.matches("^\\d{8}$".toRegex())
    }

    private fun convertBssidToDecimal(bssid: String): Long {
        return MacAddressUtils.convertToDecimal(bssid) ?: 0L
    }

    private fun decimalToBssid(decimal: Long): String {
        return MacAddressUtils.formatToColonSeparated(decimal.toString()) ?: String.format("%012X", decimal)
            .chunked(2).joinToString(":")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}