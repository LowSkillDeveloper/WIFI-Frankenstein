package com.lsd.wififrankenstein.ui.wifiscanner

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.notification.NotificationMessage
import com.lsd.wififrankenstein.ui.notification.NotificationService
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel
import com.lsd.wififrankenstein.ui.updates.UpdateChecker
import com.lsd.wififrankenstein.util.VendorChecker
import com.lsd.wififrankenstein.util.calculateDistanceString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.flow.collect


class WiFiScannerFragment : Fragment() {

    private var _binding: FragmentWifiScannerBinding? = null
    private val binding get() = _binding!!

    private lateinit var notificationService: NotificationService


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

    private fun shouldCheckUpdates(): Boolean {
        return requireActivity().getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("check_updates_on_open", true)
    }

    private fun shouldScanOnStartup(): Boolean {
        val prefs = requireActivity().getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("scan_on_startup", true)
    }

    private fun initUI() {
        wifiAdapter = WifiAdapter(emptyList())
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11+
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                viewModel.clearResults()
                startWifiScanInternal()
                hasScanned = true
            }
        } else {
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
                startWifiScanInternal()
                hasScanned = true
            }
        }
    }

    private fun startWifiScanInternal() {
        val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModel.startWifiScan()
        } else {
            wifiManager.startScan()
            viewModel.refreshWifiList()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
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
        binding.progressBarDatabaseCheck.visibility = View.VISIBLE
        binding.progressBarDatabaseCheck.isIndeterminate = true
    }

    private fun hideProgressBar() {
        binding.progressBarDatabaseCheck.isIndeterminate = false
        binding.progressBarDatabaseCheck.progress = 0
        ObjectAnimator.ofInt(binding.progressBarDatabaseCheck, "progress", 100)
            .setDuration(1000)
            .start()
        binding.progressBarDatabaseCheck.postDelayed({
            binding.progressBarDatabaseCheck.visibility = View.GONE
        }, 1300)
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
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialogView.findViewById<Button>(R.id.action_copy_ssid).setOnClickListener {
            copyToClipboard(getString(R.string.ssid), selectedWifi?.SSID)
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.action_copy_bssid).setOnClickListener {
            copyToClipboard(getString(R.string.bssid), selectedWifi?.BSSID)
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.action_check_vendor).setOnClickListener {
            selectedWifi?.BSSID?.let { showVendorDialog(it.uppercase(Locale.getDefault())) }
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.action_calculate_distance).setOnClickListener {
            selectedWifi?.let { showDistanceDialog(it) }
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.action_connect_wps).setOnClickListener {
            selectedWifi?.let { connectUsingWPSButton(it) }
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.action_connect_wps_pin).setOnClickListener {
            selectedWifi?.let { wifi ->
                showWpsPinDialog(wifi)
                dialog.dismiss()
            } ?: Toast.makeText(requireContext(), "Wi-Fi network is not selected", Toast.LENGTH_SHORT).show()
        }

        dialogView.findViewById<Button>(R.id.action_generate_wps).setOnClickListener {
            val intent = Intent(requireContext(), WpsGeneratorActivity::class.java).apply {
                putExtra("BSSID", selectedWifi?.BSSID)
            }
            Log.d("WiFiScannerFragment", "Selected BSSID: ${selectedWifi?.BSSID}")
            startActivity(intent)
            dialog.dismiss()
        }

        dialog.show()
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
