package com.lsd.wififrankenstein.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentSettingsBinding
import com.lsd.wififrankenstein.databinding.ItemSettingsInterfaceBinding
import com.lsd.wififrankenstein.ui.airodump.InterfaceStatus
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.util.ChrootDiagnostics
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.ChrootManagerSingleton
import com.lsd.wififrankenstein.util.RootlessManager
import com.lsd.wififrankenstein.util.WiFiManagerWrapper
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsFragment : Fragment() {

    private var isDeveloperCardExpanded = false

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<SettingsViewModel>()
    private lateinit var wlanInterfaceViewModel: WlanInterfaceManagerViewModel
    private lateinit var iconAdapter: AppIconAdapter
    private var settingsInterfaceAdapter: SettingsInterfaceAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        wlanInterfaceViewModel =
            ViewModelProvider(requireActivity()).get(WlanInterfaceManagerViewModel::class.java)

        setupExpandButtons()
        setupDatabaseSettingsButton()
        setupThemeRadioGroup()
        setupColorThemeRadioGroup()
        setupSwitches()
        setupStorageAccessButton()
        setupIconSettings()
        setupAPI3WiFiSettings()
        observeViewModel()
        setupDeveloperSettings()
        setupLoggingSettings()
        setupButtons()
        setupThrottleWarningBanner()
        setupRootChrootSettings()
        setupStartPageSettings()
        setupWlanInterfaceManager()

        binding.layoutDbSettingsContent.visibility = View.VISIBLE
        binding.layoutAppSettingsContent.visibility = View.VISIBLE
    }

    private fun setupLoggingSettings() {
        binding.switchEnableLogging.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setEnableLogging(isChecked)
            if (isChecked) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.logging_enabled),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.logging_disabled),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.buttonDeleteLogFolder.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val deleted = viewModel.deleteLogFolder()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(if (deleted) R.string.log_folder_deleted else R.string.log_folder_delete_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.buttonShareLastLog.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val logFile = viewModel.getLastLogFile()
                withContext(Dispatchers.Main) {
                    if (logFile != null && logFile.exists()) {
                        shareLogFile(logFile)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.no_logs_found),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun shareLogFile(logFile: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                logFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.st_share_log_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, getString(R.string.st_share_log_chooser)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.st_error_share_log), Toast.LENGTH_SHORT)
                .show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStorageAccessInfo()
    }

    private val requestLegacyPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updateStorageAccessInfo()
    }

    private fun requestLegacyStoragePermissions() {
        requestLegacyPermissions.launch(
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        )
    }

    private fun setupButtons() {
        binding.buttonNotificationSettings.setOnClickListener {
            findNavController().navigate(R.id.action_nav_settings_to_notificationSettingsFragment)
        }

        binding.buttonInAppDatabase.setOnClickListener {
            findNavController().navigate(R.id.action_nav_settings_to_inAppDatabaseFragment)
        }
    }

    private fun setupStartPageSettings() {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val startPage = prefs.getString("start_page", "wifi_scanner")

        when (startPage) {
            "all_features" -> binding.radioButtonStartAllFeatures.isChecked = true
            else -> binding.radioButtonStartWifiScanner.isChecked = true
        }

        binding.radioGroupStartPage.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.radioButtonStartWifiScanner -> "wifi_scanner"
                else -> "all_features"
            }
            viewModel.setStartPage(value)
        }
    }

    private fun setupExpandButtons() {
        binding.buttonExpandDbSettings.setOnClickListener {
            toggleExpansion(binding.layoutDbSettingsContent, binding.buttonExpandDbSettings)
        }
        binding.buttonExpandNotificationSettings.setOnClickListener {
            toggleExpansion(
                binding.layoutNotificationSettingsContent,
                binding.buttonExpandNotificationSettings
            )
        }
        binding.buttonExpandLoggingSettings.setOnClickListener {
            toggleExpansion(
                binding.layoutLoggingSettingsContent,
                binding.buttonExpandLoggingSettings
            )
        }
        binding.buttonExpandAppSettings.setOnClickListener {
            toggleExpansion(binding.layoutAppSettingsContent, binding.buttonExpandAppSettings)
        }
        binding.buttonExpandThemeSettings.setOnClickListener {
            toggleExpansion(binding.layoutThemeSettingsContent, binding.buttonExpandThemeSettings)
        }
        binding.buttonExpandAPI3WiFiSettings.setOnClickListener {
            toggleExpansion(
                binding.layoutAPI3WiFiSettingsContent,
                binding.buttonExpandAPI3WiFiSettings
            )
        }
        binding.buttonExpandWlanInterfaceManager.setOnClickListener {
            toggleExpansion(
                binding.layoutWlanInterfaceManagerContent,
                binding.buttonExpandWlanInterfaceManager
            )
        }
    }

    private fun toggleExpansion(layout: View, button: MaterialButton) {
        if (layout.isVisible) {
            layout.visibility = View.GONE
            button.setIconResource(R.drawable.ic_expand_more)
        } else {
            layout.visibility = View.VISIBLE
            button.setIconResource(R.drawable.ic_expand_less)
        }
    }

    private fun setupIconSettings() {
        val iconSpinner: Spinner = binding.spinnerAppIcon

        val icons = listOf(
            Pair(getString(R.string.icon_default), R.mipmap.ic_launcher),
            Pair(getString(R.string.icon_3wifi), R.mipmap.ic_launcher_3wifi),
            Pair(getString(R.string.icon_anti3wifi), R.mipmap.ic_launcher_anti3wifi),
            Pair(getString(R.string.icon_p3wifi), R.mipmap.ic_launcher_p3wifi),
            Pair(getString(R.string.icon_p3wifi_pixel), R.mipmap.ic_launcher_p3wifi_pixel)
        )

        iconAdapter = AppIconAdapter(requireContext(), icons)
        iconSpinner.adapter = iconAdapter

        var initialSetup = true

        iconSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (initialSetup) {
                    initialSetup = false
                    return
                }
                val selectedIcon = when (position) {
                    0 -> "default"
                    1 -> "3wifi"
                    2 -> "anti3wifi"
                    3 -> "p3wifi"
                    4 -> "p3wifi_pixel"
                    else -> "default"
                }
                viewModel.setAppIcon(selectedIcon)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        viewModel.currentAppIcon.observe(viewLifecycleOwner) { icon ->
            val position = when (icon) {
                "3wifi" -> 1
                "anti3wifi" -> 2
                "p3wifi" -> 3
                "p3wifi_pixel" -> 4
                else -> 0
            }
            if (iconSpinner.selectedItemPosition != position) {
                iconSpinner.setSelection(position)
            }
        }
    }

    private fun setupDeveloperSettings() {
        binding.buttonExpandDeveloperSettings.setOnClickListener {
            if (!isDeveloperCardExpanded) {
                showDeveloperWarning()
            } else {
                toggleExpansion(
                    binding.layoutDeveloperSettingsContent,
                    binding.buttonExpandDeveloperSettings
                )
            }
        }

        binding.switchShowAdvancedUploadOptions.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showAdvancedUploadWarning()
            } else {
                viewModel.setShowAdvancedUploadOptions(false)
            }
        }

        viewModel.showAdvancedUploadOptions.observe(viewLifecycleOwner) { isEnabled ->
            binding.switchShowAdvancedUploadOptions.isChecked = isEnabled
        }

        binding.switchDummyNetworkMode.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDummyNetworkMode(isChecked)
        }

        binding.layoutDeveloperSettingsContent.visibility = View.GONE
        binding.buttonExpandDeveloperSettings.setIconResource(R.drawable.ic_expand_more)

        viewModel.dummyNetworkMode.observe(viewLifecycleOwner) { isDummyMode ->
            binding.switchDummyNetworkMode.isChecked = isDummyMode
        }

        binding.switchShowRootWithoutRoot.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setShowRootWithoutRoot(isChecked)
        }

        viewModel.showRootWithoutRoot.observe(viewLifecycleOwner) { isEnabled ->
            binding.switchShowRootWithoutRoot.isChecked = isEnabled
        }

        binding.switchIgnoreChrootCheck.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setIgnoreChrootCheck(isChecked)
        }

        viewModel.ignoreChrootCheck.observe(viewLifecycleOwner) { isEnabled ->
            binding.switchIgnoreChrootCheck.isChecked = isEnabled
        }

        binding.switchSuppressPollingLogs.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setSuppressInterfacePollingLogs(isChecked)
        }

        viewModel.suppressInterfacePollingLogs.observe(viewLifecycleOwner) { isSuppressed ->
            binding.switchSuppressPollingLogs.isChecked = isSuppressed
        }

        binding.switchAutoScanInterfaces.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoScanInterfaces(isChecked)
        }

        viewModel.autoScanInterfaces.observe(viewLifecycleOwner) { enabled ->
            binding.switchAutoScanInterfaces.isChecked = enabled
        }

        binding.switchExtendedPollInterval.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setExtendedPollInterval(isChecked)
        }

        viewModel.extendedPollInterval.observe(viewLifecycleOwner) { enabled ->
            binding.switchExtendedPollInterval.isChecked = enabled
        }
    }

    private fun showAdvancedUploadWarning() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.advanced_upload_warning_title)
            .setMessage(R.string.advanced_upload_warning_message)
            .setPositiveButton(R.string.i_understand) { _, _ ->
                viewModel.setShowAdvancedUploadOptions(true)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                binding.switchShowAdvancedUploadOptions.isChecked = false
            }
            .show()
    }

    private fun showDeveloperWarning() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.developer_warning_title)
            .setMessage(R.string.developer_warning_message)
            .setPositiveButton(R.string.i_understand) { _, _ ->
                isDeveloperCardExpanded = true
                toggleExpansion(
                    binding.layoutDeveloperSettingsContent,
                    binding.buttonExpandDeveloperSettings
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupDatabaseSettingsButton() {
        binding.buttonDbSettings.setOnClickListener {
            findNavController().navigate(R.id.action_nav_settings_to_dbSetupFragment)
        }
    }

    private fun setupThemeRadioGroup() {
        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.radioButtonSystemTheme -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                R.id.radioButtonLightTheme -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioButtonDarkTheme -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            viewModel.setTheme(theme)
        }
    }

    private fun setupStorageAccessButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding.buttonRequestStorageAccess.visibility = View.VISIBLE
            binding.buttonRequestStorageAccess.setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestStorageAccess()
                } else {
                    requestLegacyStoragePermissions()
                }
            }
        } else {
            binding.buttonRequestStorageAccess.visibility = View.GONE
        }
        updateStorageAccessInfo()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestStorageAccess() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = "package:${requireContext().packageName}".toUri()
        }
        requestPermissionLauncher.launch(intent)
    }

    private fun updateStorageAccessInfo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.buttonRequestStorageAccess.visibility = View.VISIBLE
            if (Environment.isExternalStorageManager()) {
                binding.textViewWarningStorageAccessInfo.visibility = View.GONE
                binding.textViewStorageAccessInfo.visibility = View.VISIBLE
                binding.textViewStorageAccessInfo.text = getString(R.string.storage_access_granted)
                binding.textViewStorageAccessInfo.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.green_500
                    )
                )
            } else {
                binding.textViewWarningStorageAccessInfo.visibility = View.VISIBLE
                binding.textViewStorageAccessInfo.visibility = View.VISIBLE
                binding.textViewStorageAccessInfo.text = getString(R.string.storage_access_info)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            binding.buttonRequestStorageAccess.visibility = View.VISIBLE
            val hasReadPermission = ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            val hasWritePermission = ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

            if (hasReadPermission && hasWritePermission) {
                binding.textViewWarningStorageAccessInfo.visibility = View.GONE
                binding.textViewStorageAccessInfo.visibility = View.VISIBLE
                binding.textViewStorageAccessInfo.text = getString(R.string.storage_access_granted)
                binding.textViewStorageAccessInfo.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.green_500
                    )
                )
            } else {
                binding.textViewWarningStorageAccessInfo.visibility = View.VISIBLE
                binding.textViewStorageAccessInfo.visibility = View.VISIBLE
                binding.textViewStorageAccessInfo.text = getString(R.string.storage_access_info)
            }
        } else {
            binding.buttonRequestStorageAccess.visibility = View.GONE
            binding.textViewWarningStorageAccessInfo.visibility = View.GONE
            binding.textViewStorageAccessInfo.visibility = View.GONE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_ALL_FILES_ACCESS_PERMISSION) {
            updateStorageAccessInfo()
        }
    }

    private fun setupColorThemeRadioGroup() {
        binding.radioGroupColorTheme.setOnCheckedChangeListener { _, checkedId ->
            val colorTheme = when (checkedId) {
                R.id.radioButtonPurpleTheme -> "purple"
                R.id.radioButtonGreenTheme -> "green"
                R.id.radioButtonBlueTheme -> "blue"
                else -> "green"
            }
            viewModel.setColorTheme(colorTheme)
        }
    }

    private fun setupSwitches() {
        binding.switchPrioritizeNetworksWithData.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setPrioritizeNetworksWithData(isChecked)
            binding.switchAutoScrollToNetworksWithData.isEnabled = isChecked
            if (!isChecked) {
                binding.switchAutoScrollToNetworksWithData.isChecked = false
                viewModel.setAutoScrollToNetworksWithData(false)
            }
        }

        binding.switchAutoScrollToNetworksWithData.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoScrollToNetworksWithData(isChecked)
        }

        binding.switchCheckUpdates.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setCheckUpdatesOnOpen(isChecked)
        }

        binding.switchCheckWpaSec.setOnCheckedChangeListener { _, isChecked ->
            requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean("check_wpasec", isChecked).apply()
        }

        binding.switchScanOnStartup.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setScanOnStartup(isChecked)
        }

        binding.switchEnableRoot.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setEnableRoot(isChecked)
        }

    }

    private fun setupAPI3WiFiSettings() {
        binding.editTextMaxPoints.setText(viewModel.getMaxPointsPerRequest().toString())
        binding.editTextRequestDelay.setText(viewModel.getRequestDelay().toString())
        binding.editTextConnectTimeout.setText(viewModel.getConnectTimeout().toString())
        binding.editTextReadTimeout.setText(viewModel.getReadTimeout().toString())
        binding.switchCacheResults.isChecked = viewModel.getCacheResults()
        binding.switchTryAlternativeUrl.isChecked = viewModel.getTryAlternativeUrl()
        binding.switchIgnoreSSLCertificate.isChecked = viewModel.getIgnoreSSLCertificate()
        binding.switchUsePostMethod.isChecked = viewModel.getUsePostMethod()
        binding.switchIncludeAppIdentifier.isChecked = viewModel.getIncludeAppIdentifier()

        binding.switchIncludeAppIdentifier.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setIncludeAppIdentifier(isChecked)
        }

        binding.switchUsePostMethod.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setUsePostMethod(isChecked)
        }

        binding.buttonSaveAPISettings.setOnClickListener {
            saveAPI3WiFiSettings()
        }

        binding.buttonResetAPISettings.setOnClickListener {
            resetAPI3WiFiSettings()
        }

        binding.buttonClearAPICache.setOnClickListener {
            viewModel.clearAPI3WiFiCache()
            Toast.makeText(context, getString(R.string.st_api_cache_cleared), Toast.LENGTH_SHORT)
                .show()
        }

        setupInfoButtons()
    }

    private fun setupInfoButtons() {
        binding.editTextMaxPoints.setOnClickListener { showInfoToast(R.string.max_points_info) }
        binding.editTextRequestDelay.setOnClickListener { showInfoToast(R.string.request_delay_info) }
        binding.editTextConnectTimeout.setOnClickListener { showInfoToast(R.string.connect_timeout_info) }
        binding.editTextReadTimeout.setOnClickListener { showInfoToast(R.string.read_timeout_info) }
        binding.switchCacheResults.setOnLongClickListener {
            showInfoToast(R.string.cache_results_info)
            true
        }
        binding.switchTryAlternativeUrl.setOnLongClickListener {
            showInfoToast(R.string.try_alternative_url_info)
            true
        }
        binding.switchIgnoreSSLCertificate.setOnLongClickListener {
            showInfoToast(R.string.ignore_ssl_certificate_info)
            true
        }
    }

    private fun showInfoToast(stringResId: Int) {
        Toast.makeText(context, getString(stringResId), Toast.LENGTH_LONG).show()
    }

    private fun setupThrottleWarningBanner() {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val hideWarning = prefs.getBoolean("hide_throttle_warning", false)
        val wifiManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requireContext().getSystemService(WifiManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            requireContext().getSystemService(Context.WIFI_SERVICE) as WifiManager
        }
        val wrapper = WiFiManagerWrapper(wifiManager)
        val isThrottleEnabled = wrapper.isScanThrottleEnabled()

        if (isThrottleEnabled && !hideWarning) {
            binding.throttleWarningBanner.throttleWarningRoot.visibility = View.VISIBLE
        } else {
            binding.throttleWarningBanner.throttleWarningRoot.visibility = View.GONE
        }

        binding.throttleWarningBanner.throttleWarningRoot.setOnClickListener {
            showThrottleWarningDialog()
        }

        binding.throttleWarningBanner.buttonCloseThrottleWarning.setOnClickListener {
            binding.throttleWarningBanner.throttleWarningRoot.visibility = View.GONE
        }
    }

    private fun showThrottleWarningDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.throttle_warning_title))
            .setMessage(getString(R.string.throttle_warning_message))
            .setPositiveButton(getString(R.string.ok), null)
            .setNegativeButton(getString(R.string.dont_show_again)) { _, _ ->
                val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("hide_throttle_warning", true).apply()
                binding.throttleWarningBanner.throttleWarningRoot.visibility = View.GONE
            }
            .show()
    }

    private fun saveAPI3WiFiSettings() {
        val maxPoints = binding.editTextMaxPoints.text.toString().toIntOrNull() ?: 99

        if (maxPoints > 100) {
            showWarningDialog()
        }

        viewModel.setMaxPointsPerRequest(maxPoints)
        viewModel.setRequestDelay(
            binding.editTextRequestDelay.text.toString().toLongOrNull() ?: 1000
        )
        viewModel.setConnectTimeout(
            binding.editTextConnectTimeout.text.toString().toIntOrNull() ?: 5000
        )
        viewModel.setReadTimeout(binding.editTextReadTimeout.text.toString().toIntOrNull() ?: 5000)
        viewModel.setCacheResults(binding.switchCacheResults.isChecked)
        viewModel.setTryAlternativeUrl(binding.switchTryAlternativeUrl.isChecked)
        viewModel.setIgnoreSSLCertificate(binding.switchIgnoreSSLCertificate.isChecked)

        Toast.makeText(context, getString(R.string.st_api_settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun showWarningDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.warning)
            .setMessage(R.string.max_points_warning)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun setupRootChrootSettings() {
        binding.buttonExpandRootChrootSettings.setOnClickListener {
            toggleExpansion(binding.layoutRootChrootContent, binding.buttonExpandRootChrootSettings)
        }

        val chrootManager = ChrootManager(requireContext())

        val rootPrefs =
            requireContext().getSharedPreferences("com.lsd.wififrankenstein", Context.MODE_PRIVATE)
        val wasRootEnabled = rootPrefs.getBoolean("enable_root", false)

        binding.switchEnableRoot.isChecked = wasRootEnabled

        binding.switchEnableRoot.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !wasRootEnabled) {
                requestRootPermission()
            } else if (!isChecked) {
                viewModel.setEnableRoot(false)
            }
        }

        updateChrootUI(chrootManager)

        binding.buttonInstallChroot.setOnClickListener {
            val ct = chrootManager.getChrootType()
            if (ct is com.lsd.wififrankenstein.util.ChrootType.Rootless ||
                ct is com.lsd.wififrankenstein.util.ChrootType.RootWithoutChroot
            ) {
                startRootlessInstall()
            } else {
                showChrootInstallDialog(chrootManager)
            }
        }

        binding.buttonReinstallChroot.setOnClickListener {
            showChrootInstallDialog(chrootManager, true)
        }

        binding.buttonMountChroot.setOnClickListener {
            mountChroot(chrootManager)
        }

        binding.buttonUnmountChroot.setOnClickListener {
            unmountChroot(chrootManager)
        }

        binding.buttonUninstallChroot.setOnClickListener {
            showUninstallChrootDialog(chrootManager)
        }

        binding.buttonDiagnoseChroot.setOnClickListener {
            diagnoseChroot(chrootManager)
        }
    }

    private fun requestRootPermission() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.root_permission_request)
            .setMessage(R.string.root_permission_message)
            .setPositiveButton(R.string.request_root) { _, _ ->
                requestRootCoroutine()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestRootCoroutine() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = Shell.cmd("id").exec()
                val isRoot = result.out.any { it.contains("uid=0") }
                withContext(Dispatchers.Main) {
                    if (isRoot) {
                        viewModel.setEnableRoot(true)
                        Toast.makeText(requireContext(), R.string.root_granted, Toast.LENGTH_SHORT)
                            .show()
                        ChrootManagerSingleton.get(requireContext()).resetChrootCaches()
                        updateChrootUI(ChrootManager(requireContext()))
                    } else {
                        Toast.makeText(requireContext(), R.string.root_denied, Toast.LENGTH_SHORT)
                            .show()
                        binding.switchEnableRoot.isChecked = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    Toast.makeText(requireContext(), R.string.root_error, Toast.LENGTH_SHORT).show()
                    binding.switchEnableRoot.isChecked = false
                }
            }
        }
    }

    private fun updateChrootUI(chrootManager: ChrootManager) {
        val chrootType = chrootManager.getChrootType()

        when (chrootType) {
            is com.lsd.wififrankenstein.util.ChrootType.Root -> {
                binding.textViewChrootStatus.text = getString(R.string.chroot_is_installed)
                binding.buttonInstallChroot.visibility = View.GONE
                binding.buttonReinstallChroot.visibility = View.VISIBLE
                binding.buttonMountChroot.visibility = View.VISIBLE
                binding.buttonUnmountChroot.visibility = View.VISIBLE
                binding.buttonUninstallChroot.visibility = View.VISIBLE
                binding.buttonDiagnoseChroot.visibility = View.VISIBLE
                binding.buttonInstallChroot.text = getString(R.string.install_chroot)
                binding.buttonReinstallChroot.text = getString(R.string.reinstall_chroot)
            }

            is com.lsd.wififrankenstein.util.ChrootType.RootMissing -> {
                binding.textViewChrootStatus.text = getString(R.string.chroot_not_installed)
                binding.buttonInstallChroot.visibility = View.VISIBLE
                binding.buttonInstallChroot.text = getString(R.string.install_chroot)
                binding.buttonReinstallChroot.visibility = View.GONE
                binding.buttonMountChroot.visibility = View.GONE
                binding.buttonUnmountChroot.visibility = View.GONE
                binding.buttonUninstallChroot.visibility = View.GONE
                binding.buttonDiagnoseChroot.visibility = View.VISIBLE
            }

            com.lsd.wififrankenstein.util.ChrootType.None -> {
                binding.textViewChrootStatus.text = getString(R.string.root_required)
                binding.buttonInstallChroot.visibility = View.GONE
                binding.buttonInstallChroot.text = getString(R.string.install_chroot)
                binding.buttonReinstallChroot.visibility = View.GONE
                binding.buttonMountChroot.visibility = View.GONE
                binding.buttonUnmountChroot.visibility = View.GONE
                binding.buttonUninstallChroot.visibility = View.GONE
                binding.buttonDiagnoseChroot.visibility = View.GONE
            }

            is com.lsd.wififrankenstein.util.ChrootType.RootWithoutChroot -> {
                binding.textViewChrootStatus.text = getString(R.string.chroot_not_supported_proot)
                binding.buttonInstallChroot.visibility = View.VISIBLE
                binding.buttonInstallChroot.text = getString(R.string.install_rootless)
                binding.buttonReinstallChroot.visibility = View.GONE
                binding.buttonMountChroot.visibility = View.GONE
                binding.buttonUnmountChroot.visibility = View.GONE
                binding.buttonUninstallChroot.visibility = View.GONE
                binding.buttonDiagnoseChroot.visibility = View.GONE
            }

            is com.lsd.wififrankenstein.util.ChrootType.Rootless -> {
                binding.textViewChrootStatus.text = getString(R.string.rootless_mode)
                binding.buttonInstallChroot.visibility = View.VISIBLE
                binding.buttonInstallChroot.text = getString(R.string.install_rootless)
                binding.buttonReinstallChroot.visibility = View.GONE
                binding.buttonMountChroot.visibility = View.GONE
                binding.buttonUnmountChroot.visibility = View.GONE
                binding.buttonUninstallChroot.visibility = View.GONE
                binding.buttonDiagnoseChroot.visibility = View.GONE
            }
        }
    }

    private fun showChrootInstallDialog(
        chrootManager: ChrootManager,
        isReinstall: Boolean = false
    ) {
        val titleRes = if (isReinstall) R.string.reinstall_chroot else R.string.install_chroot
        val messageRes =
            if (isReinstall) R.string.reinstall_chroot_confirm else R.string.install_chroot_confirm
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.yes) { _, _ ->
                if (isReinstall) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            chrootManager.uninstall()
                            withContext(Dispatchers.Main) {
                                installChroot(chrootManager)
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.chroot_uninstall_error, e.message),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } else {
                    installChroot(chrootManager)
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun showUninstallChrootDialog(chrootManager: ChrootManager) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.uninstall_chroot)
            .setMessage(R.string.uninstall_chroot_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                uninstallChroot(chrootManager)
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun isMobileNetwork(): Boolean {
        val connectivityManager =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo ?: return false
            return activeNetworkInfo.type != ConnectivityManager.TYPE_WIFI
        }
    }

    private fun startRootlessInstall() {
        if (isMobileNetwork()) {
            showMobileWarningForRootless()
            return
        }
        val cm = ChrootManager(requireContext())
        val rootlessManager = RootlessManager(requireContext())
        if (!rootlessManager.isSupportedArchitecture()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.rootless_arch_required),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.install_rootless)
            .setMessage(getString(R.string.rootless_preparing))
            .setCancelable(false)
            .show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val chrootInfo = cm.getChrootInfo()
            val archive = chrootInfo?.let {
                if (cm.isAarch64()) it.aarch64 else it.armhf
            }
            if (archive == null) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.rootless_failed_rootfs_url),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            val success = rootlessManager.setupRootfs(
                onProgress = { progress ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        progressDialog.setMessage(getString(R.string.rootless_progress, progress))
                    }
                },
                onStatusUpdate = { status ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        progressDialog.setMessage(status)
                    }
                },
                downloadUrl = archive.download_url,
                onDiagnosticUpdate = { name, icon, result ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        progressDialog.setMessage(
                            getString(R.string.rootless_diagnostic_line, icon, name, result)
                        )
                    }
                }
            )
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.rootless_setup_completed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                updateChrootUI(cm)
            }
        }
    }

    private fun showMobileWarningForRootless() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.warning)
            .setMessage(R.string.mobile_download_warning)
            .setPositiveButton(R.string.mobile_download_continue) { _, _ -> startRootlessInstall() }
            .setNegativeButton(R.string.mobile_download_cancel, null)
            .show()
    }

    private fun installChroot(chrootManager: ChrootManager) {
        if (!chrootManager.isArmArchitecture()) {
            val archLabel = chrootManager.getArchitecture().label
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rs_unsupported_arch)
                .setMessage(getString(R.string.chroot_arch_warning, archLabel))
                .setPositiveButton(R.string.continue_text) { _, _ -> doInstallChroot(chrootManager) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        doInstallChroot(chrootManager)
    }

    private fun doInstallChroot(chrootManager: ChrootManager) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chroot_installing)
            .setMessage(getString(R.string.chroot_installing))
            .setCancelable(false)
            .show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = chrootManager.downloadAndInstall(
                    onProgress = { progress ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            dialog.setMessage(getString(R.string.progress_percent, progress))
                        }
                    },
                    onStatusUpdate = { status ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            dialog.setMessage(status)
                        }
                    },
                    onCancelled = { false }
                )
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    if (success) {
                        Toast.makeText(
                            requireContext(),
                            R.string.chroot_installation_completed,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {

                    }
                    updateChrootUI(chrootManager)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.chroot_install_error, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun uninstallChroot(chrootManager: ChrootManager) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                chrootManager.uninstall()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        R.string.chroot_uninstalled,
                        Toast.LENGTH_SHORT
                    ).show()
                    updateChrootUI(chrootManager)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.chroot_uninstall_error, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun mountChroot(chrootManager: ChrootManager) {
        try {
            chrootManager.mountChroot()
            Toast.makeText(requireContext(), R.string.chroot_mounted, Toast.LENGTH_SHORT).show()
            updateChrootUI(chrootManager)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.chroot_mount_error, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun unmountChroot(chrootManager: ChrootManager) {
        try {
            chrootManager.unmountChroot()
            Toast.makeText(requireContext(), R.string.chroot_unmounted, Toast.LENGTH_SHORT).show()
            updateChrootUI(chrootManager)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.chroot_unmount_error, e.message),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun addDiagCard(container: LinearLayout, name: String, icon: String, result: String) {
        val iconColor = when {
            icon.contains("✗") -> 0xFFC62828.toInt()
            icon.contains("!") -> 0xFFE65100.toInt()
            icon.contains("?") -> 0xFF757575.toInt()
            else -> 0xFF2E7D32.toInt()
        }
        val cardBg = when {
            icon.contains("✗") -> 0x1AFF5252.toInt()
            icon.contains("!") -> 0x1AFF9800.toInt()
            icon.contains("?") -> 0x1A9E9E9E.toInt()
            else -> 0x1A69F0AE.toInt()
        }
        val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(4)) }
            cardElevation = 0f
            radius = dp(8).toFloat()
            setContentPadding(dp(12), dp(8), dp(12), dp(8))
            setCardBackgroundColor(cardBg)
        }
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val top = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(requireContext()).apply {
            text = icon.trim()
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(iconColor)
        })
        top.addView(TextView(requireContext()).apply {
            text = name
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(8), 0, dp(8), 0) }
        })
        col.addView(top)
        col.addView(TextView(requireContext()).apply {
            text = result
            textSize = 11f
            alpha = 0.7f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(20), dp(2), 0, 0) }
        })
        card.addView(col)
        container.addView(card)
    }

    private fun diagnoseChroot(chrootManager: ChrootManager) {
        val scroll = ScrollView(requireContext())
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        scroll.addView(container)
        val height = (resources.displayMetrics.heightPixels * 0.6).toInt()
        scroll.layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.st_chroot_diagnostics_title)
            .setView(scroll)
            .setCancelable(false)
            .setNeutralButton(getString(R.string.copy_log), null)
            .setNegativeButton(R.string.close, null)
            .show()

        val loadingText = TextView(requireContext()).apply {
            text = getString(R.string.st_chroot_diagnostics_running)
            textSize = 13f
            alpha = 0.6f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(16), 0, dp(8)) }
        }
        container.addView(loadingText)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val results = chrootManager.runPermissionDiagnostic { result ->
                    val icon = when {
                        !result.success -> " ✗"
                        result.name == "selinux_status" && result.output.trim() == "Enforcing" -> " !"
                        result.name == "knox_indicators" && result.output.contains("KNOX") -> " !"
                        result.name == "busybox_linkage" && result.output.contains("DYNAMIC") -> " !"
                        result.name == "system_chroot" && result.output.contains("NOT_FOUND") -> " ?"
                        result.name == "kernel_chroot_config" && result.output.contains("CONFIG_UNKNOWN") -> " ?"
                        result.name == "seccomp_status" && result.output.contains("N/A") -> " ?"
                        result.name == "chroot_sysctl" && result.output.contains("N/A") -> " ?"
                        result.name == "magiskpolicy" && !result.success -> " !"
                        else -> " ✓"
                    }
                    val short = buildDiagShortResult(result)
                    view?.post {
                        container.removeView(loadingText)
                        addDiagCard(container, result.description, icon, short)
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }

                val allRules = results.flatMap { it.suggestedRules }.distinct()
                withContext(Dispatchers.Main) {
                    dialog.setButton(
                        android.content.DialogInterface.BUTTON_NEGATIVE,
                        "Close"
                    ) { _, _ -> dialog.dismiss() }
                    dialog.setButton(
                        android.content.DialogInterface.BUTTON_NEUTRAL,
                        getString(R.string.copy_log)
                    ) { _, _ ->
                        val log = buildSettingsDiagnosticLog(results)
                        val clipboard =
                            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "chroot_diagnostic",
                                log
                            )
                        )
                        Toast.makeText(requireContext(), R.string.log_copied, Toast.LENGTH_SHORT)
                            .show()
                    }
                    if (allRules.isNotEmpty()) {
                        val hasMagisk = results.find { it.name == "magiskpolicy" }?.success == true
                        if (hasMagisk) {
                            dialog.setButton(
                                android.content.DialogInterface.BUTTON_POSITIVE,
                                getString(R.string.st_diag_apply_magiskpolicy)
                            ) { _, _ ->
                                applyRules(chrootManager, results)
                            }
                        } else {
                            dialog.setButton(
                                android.content.DialogInterface.BUTTON_POSITIVE,
                                getString(R.string.st_diag_copy_sepolicy_rule)
                            ) { _, _ ->
                                val diag = ChrootDiagnostics(
                                    "/data/local/wififrankenstein/tools/busybox",
                                    "/data/local/wififrankenstein/chroot"
                                )
                                val content = diag.buildSepolicyModuleContent(results)
                                val clipboard =
                                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "sepolicy.rule",
                                        content
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    container.removeView(loadingText)
                    addDiagCard(
                        container,
                        getString(R.string.ws_error),
                        " ✗",
                        e.message ?: getString(R.string.st_diag_unknown)
                    )
                    dialog.setButton(
                        android.content.DialogInterface.BUTTON_NEGATIVE,
                        getString(R.string.close)
                    ) { _, _ -> dialog.dismiss() }
                    dialog.setButton(
                        android.content.DialogInterface.BUTTON_NEUTRAL,
                        getString(R.string.copy_log)
                    ) { _, _ ->
                        val clipboard =
                            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "chroot_diagnostic_error",
                                e.message ?: getString(R.string.svc_unknown_error)
                            )
                        )
                        Toast.makeText(requireContext(), R.string.log_copied, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }

    private fun buildSettingsDiagnosticLog(results: List<ChrootDiagnostics.StageResult>): String =
        buildString {
            appendLine("WIFI-Frankenstein Chroot Diagnostics")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE}, SDK: ${Build.VERSION.SDK_INT}")
            appendLine()
            for (r in results) {
                appendLine("=== ${r.description} ===")
                r.output.lineSequence().forEach { line ->
                    if (line.isNotBlank()) appendLine(line)
                }
                if (r.exitCode != 0) appendLine("exit code: ${r.exitCode}")
                if (r.avcEntries.isNotEmpty()) {
                    appendLine("AVC denials:")
                    r.avcEntries.forEach { appendLine("  ${it.toReadable()}") }
                }
                appendLine()
            }
        }

    private fun buildDiagShortResult(result: ChrootDiagnostics.StageResult): String = buildString {
        val lines = result.output.lineSequence().filter { it.isNotBlank() }.toList()
        val informational = setOf(
            "selinux_status", "kernel_version", "proc_filesystems",
            "proc_mounts_noexec", "exec_directories", "context",
            "capabilities", "mount_sysfs", "busybox_linkage",
            "kernel_chroot_config", "seccomp_status", "knox_indicators",
            "chroot_sysctl", "proot_available"
        )
        when {
            result.name == "magiskpolicy" && result.exitCode != 0 -> append("not found")
            result.name == "knox_indicators" && lines.any { it.contains("KNOX") || it.startsWith("v") } ->
                append(lines.joinToString(", "))

            result.name in informational -> append(lines.firstOrNull()?.take(60) ?: "")
            result.exitCode != 0 -> {
                append("exit ${result.exitCode}")
                val info = lines.firstOrNull()?.take(60)
                if (info != null) append(" — $info")
            }

            else -> append(lines.firstOrNull()?.take(60) ?: "OK")
        }
        if (result.avcEntries.isNotEmpty()) append(" [${result.avcEntries.size} AVC]")
    }

    private fun applyRules(
        chrootManager: ChrootManager,
        results: List<ChrootDiagnostics.StageResult>
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val diag = ChrootDiagnostics(
                    "/data/local/wififrankenstein/tools/busybox",
                    "/data/local/wififrankenstein/chroot"
                )
                val ok = diag.applyMagiskRules(results)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        if (ok) getString(R.string.st_diag_rules_applied)
                        else getString(R.string.st_diag_rules_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.st_diag_apply_error, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun resetAPI3WiFiSettings() {
        viewModel.setMaxPointsPerRequest(99)
        viewModel.setRequestDelay(1000)
        viewModel.setConnectTimeout(5000)
        viewModel.setReadTimeout(10000)
        viewModel.setCacheResults(true)
        viewModel.setTryAlternativeUrl(true)
        viewModel.setIgnoreSSLCertificate(false)

        binding.editTextMaxPoints.setText("99")
        binding.editTextRequestDelay.setText("1000")
        binding.editTextConnectTimeout.setText("5000")
        binding.editTextReadTimeout.setText("10000")
        binding.switchCacheResults.isChecked = true
        binding.switchTryAlternativeUrl.isChecked = true
        binding.switchIgnoreSSLCertificate.isChecked = false

        Toast.makeText(context, getString(R.string.st_api_settings_reset), Toast.LENGTH_SHORT).show()
    }

    private fun observeViewModel() {
        viewModel.currentTheme.observe(viewLifecycleOwner) { theme ->
            val radioButtonId = when (theme) {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> R.id.radioButtonSystemTheme
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.radioButtonLightTheme
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.radioButtonDarkTheme
                else -> R.id.radioButtonSystemTheme
            }
            binding.radioGroupTheme.check(radioButtonId)
        }

        viewModel.enableLogging.observe(viewLifecycleOwner) { isEnabled ->
            binding.switchEnableLogging.isChecked = isEnabled
        }

        viewModel.prioritizeNetworksWithData.observe(viewLifecycleOwner) { isPrioritized ->
            binding.switchPrioritizeNetworksWithData.isChecked = isPrioritized
            binding.switchAutoScrollToNetworksWithData.isEnabled = isPrioritized
            if (!isPrioritized) {
                binding.switchAutoScrollToNetworksWithData.isChecked = false
            }
        }

        viewModel.autoScrollToNetworksWithData.observe(viewLifecycleOwner) { isEnabled ->
            binding.switchAutoScrollToNetworksWithData.isChecked = isEnabled
        }

        viewModel.includeAppIdentifier.observe(viewLifecycleOwner) { include ->
            binding.switchIncludeAppIdentifier.isChecked = include
        }

        viewModel.currentColorTheme.observe(viewLifecycleOwner) { colorTheme ->
            val radioButtonId = when (colorTheme) {
                "purple" -> R.id.radioButtonPurpleTheme
                "green" -> R.id.radioButtonGreenTheme
                "blue" -> R.id.radioButtonBlueTheme
                else -> R.id.radioButtonGreenTheme
            }
            binding.radioGroupColorTheme.check(radioButtonId)
        }

        viewModel.usePostMethod.observe(viewLifecycleOwner) { usePost ->
            binding.switchUsePostMethod.isChecked = usePost
        }

        viewModel.scanOnStartup.observe(viewLifecycleOwner) { isChecked ->
            binding.switchScanOnStartup.isChecked = isChecked
        }

        viewModel.checkUpdatesOnOpen.observe(viewLifecycleOwner) { isChecked ->
            binding.switchCheckUpdates.isChecked = isChecked
        }

        binding.switchCheckWpaSec.isChecked = requireContext()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("check_wpasec", true)

        viewModel.enableRoot.observe(viewLifecycleOwner) { isChecked ->
            binding.switchEnableRoot.isChecked = isChecked
        }

        viewModel.themeChanged.observe(viewLifecycleOwner) { changed ->
            if (changed) {
                activity?.recreate()
                viewModel.resetThemeChangedFlag()
            }
        }

        viewModel.maxPointsPerRequest.observe(viewLifecycleOwner) { maxPoints ->
            binding.editTextMaxPoints.setText(maxPoints.toString())
        }
        viewModel.requestDelay.observe(viewLifecycleOwner) { delay ->
            binding.editTextRequestDelay.setText(delay.toString())
        }
        viewModel.connectTimeout.observe(viewLifecycleOwner) { timeout ->
            binding.editTextConnectTimeout.setText(timeout.toString())
        }
        viewModel.readTimeout.observe(viewLifecycleOwner) { timeout ->
            binding.editTextReadTimeout.setText(timeout.toString())
        }
        viewModel.cacheResults.observe(viewLifecycleOwner) { cache ->
            binding.switchCacheResults.isChecked = cache
        }
        viewModel.tryAlternativeUrl.observe(viewLifecycleOwner) { tryAlt ->
            binding.switchTryAlternativeUrl.isChecked = tryAlt
        }
        viewModel.ignoreSSLCertificate.observe(viewLifecycleOwner) { ignoreSSL ->
            binding.switchIgnoreSSLCertificate.isChecked = ignoreSSL
        }
    }

    private fun setupWlanInterfaceManager() {
        settingsInterfaceAdapter = SettingsInterfaceAdapter(
            onToggle = { ifaceName ->
                val currentMode = wlanInterfaceViewModel.interfaceStatuses.value
                    ?.firstOrNull { it.name == ifaceName }?.mode
                    ?: IwWifiManager.MODE_UNKNOWN

                val targetMode = if (currentMode == IwWifiManager.MODE_MONITOR) {
                    IwWifiManager.MODE_MANAGED
                } else {
                    IwWifiManager.MODE_MONITOR
                }

                wlanInterfaceViewModel.setInterfaceMode(ifaceName, targetMode)
            },
            onDelete = { ifaceName ->
                wlanInterfaceViewModel.removeCustomInterface(ifaceName)
            },
            isCustomCheck = { ifaceName ->
                wlanInterfaceViewModel.isCustomInterface(ifaceName)
            }
        )

        binding.recyclerViewInterfaces.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = settingsInterfaceAdapter
        }

        binding.buttonAddInterface.setOnClickListener {
            val name = binding.editTextInterfaceName.text?.toString()?.trim() ?: ""
            if (name.isNotEmpty()) {
                wlanInterfaceViewModel.addCustomInterface(name)
                binding.editTextInterfaceName.text?.clear()
            }
        }

        binding.buttonRefreshInterfaces.setOnClickListener {
            wlanInterfaceViewModel.pollInterfaceStatus()
        }

        wlanInterfaceViewModel.interfaceStatuses.observe(viewLifecycleOwner) { statuses ->
            settingsInterfaceAdapter?.submitList(statuses)
            updateInterfaceDropdowns(statuses.map { it.name })
        }

        wlanInterfaceViewModel.toastMessage.observe(viewLifecycleOwner) { msg ->
            msg?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
                wlanInterfaceViewModel.clearToastMessage()
            }
        }

        setupInterfaceAssignment()
        wlanInterfaceViewModel.startPolling()
    }

    private fun setupInterfaceAssignment() {
        binding.autoCompleteScanInterface.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val iface =
                    parent?.getItemAtPosition(position) as? String ?: return@OnItemClickListener
                wlanInterfaceViewModel.setScanInterface(iface)
            }

        binding.autoCompleteCaptureInterface.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val iface =
                    parent?.getItemAtPosition(position) as? String ?: return@OnItemClickListener
                wlanInterfaceViewModel.setCaptureInterface(iface)
            }

        binding.autoCompleteDeauthInterface.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val iface =
                    parent?.getItemAtPosition(position) as? String ?: return@OnItemClickListener
                wlanInterfaceViewModel.setDeauthInterface(iface)
            }
    }

    private fun updateInterfaceDropdowns(interfaceNames: List<String>) {
        val names = interfaceNames.toTypedArray()

        val scanAdapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            names
        )
        binding.autoCompleteScanInterface.setAdapter(scanAdapter)
        val currentScan = wlanInterfaceViewModel.getScanInterface()
        binding.autoCompleteScanInterface.setText(currentScan, false)

        val captureAdapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            names
        )
        binding.autoCompleteCaptureInterface.setAdapter(captureAdapter)
        val currentCapture = wlanInterfaceViewModel.getCaptureInterface()
        binding.autoCompleteCaptureInterface.setText(currentCapture, false)

        val deauthNames = arrayOf("") + names
        val deauthAdapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            deauthNames
        )
        binding.autoCompleteDeauthInterface.setAdapter(deauthAdapter)
        val currentDeauth = wlanInterfaceViewModel.getDeauthInterface()
        binding.autoCompleteDeauthInterface.setText(currentDeauth, false)
    }

    inner class SettingsInterfaceAdapter(
        private val onToggle: (String) -> Unit,
        private val onDelete: (String) -> Unit,
        private val isCustomCheck: (String) -> Boolean
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SettingsInterfaceAdapter.ViewHolder>() {

        private var items: List<InterfaceStatus> = emptyList()

        fun submitList(newItems: List<InterfaceStatus>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemSettingsInterfaceBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemSettingsInterfaceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.textInterfaceName.text = item.name

            if (item.subtitle != null) {
                holder.binding.textInterfaceSubtitle.text = item.subtitle
                holder.binding.textInterfaceSubtitle.visibility = View.VISIBLE
            } else {
                holder.binding.textInterfaceSubtitle.visibility = View.GONE
            }

            val modeLabel = when (item.mode) {
                IwWifiManager.MODE_MANAGED -> getString(R.string.st_iface_mode_managed)
                IwWifiManager.MODE_MONITOR -> getString(R.string.st_iface_mode_monitor)
                IwWifiManager.MODE_UNAVAILABLE -> getString(R.string.st_iface_mode_unavailable)
                else -> getString(R.string.st_iface_mode_unknown)
            }
            holder.binding.textInterfaceMode.text = modeLabel

            val modeColor = when (item.mode) {
                IwWifiManager.MODE_MANAGED -> android.R.color.holo_green_dark
                IwWifiManager.MODE_MONITOR -> android.R.color.holo_blue_dark
                IwWifiManager.MODE_UNAVAILABLE -> android.R.color.holo_red_dark
                else -> android.R.color.darker_gray
            }
            holder.binding.textInterfaceMode.setTextColor(
                ContextCompat.getColor(holder.binding.root.context, modeColor)
            )

            val isMonitor = item.mode == IwWifiManager.MODE_MONITOR
            val isUnavailable = item.mode == IwWifiManager.MODE_UNAVAILABLE
            holder.binding.buttonToggleMode.text =
                if (isMonitor) getString(R.string.st_iface_toggle_managed)
                else getString(R.string.st_iface_toggle_monitor)
            holder.binding.buttonToggleMode.isEnabled = !isUnavailable
            holder.binding.buttonToggleMode.setOnClickListener {
                onToggle(item.name)
            }

            val isCustom = isCustomCheck(item.name)
            holder.binding.buttonDeleteInterface.visibility =
                if (isCustom) View.VISIBLE else View.GONE
            holder.binding.buttonDeleteInterface.setOnClickListener {
                onDelete(item.name)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        private const val REQUEST_MANAGE_ALL_FILES_ACCESS_PERMISSION = 2
    }

    override fun onDestroyView() {
        super.onDestroyView()
        wlanInterfaceViewModel.stopPolling()
        _binding = null
    }
}
