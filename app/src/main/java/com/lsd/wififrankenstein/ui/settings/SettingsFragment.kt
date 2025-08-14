package com.lsd.wififrankenstein.ui.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
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
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentSettingsBinding
import java.io.File

class SettingsFragment : Fragment() {

    private var isDeveloperCardExpanded = false

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<SettingsViewModel>()
    private lateinit var iconAdapter: AppIconAdapter

    private lateinit var sliderClusterAggressiveness: Slider
    private lateinit var sliderMaxClusterSize: Slider
    private lateinit var sliderMarkerVisibilityZoom: Slider
    private lateinit var textViewMaxClusterSizeValue: TextView
    private lateinit var textViewMarkerVisibilityZoomValue: TextView

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

        setupExpandButtons()
        setupDatabaseSettingsButton()
        setupClearCachedDatabasesButton()
        setupThemeRadioGroup()
        setupColorThemeRadioGroup()
        setupSwitches()
        setupStorageAccessButton()
        setupIconSettings()
        setupAPI3WiFiSettings()
        setupMapSliders()
        observeViewModel()
        setupDeveloperSettings()
        setupLoggingSettings()
        setupButtons()

        binding.layoutDbSettingsContent.visibility = View.VISIBLE
        binding.layoutAppSettingsContent.visibility = View.VISIBLE
    }

    private fun setupLoggingSettings() {
        binding.switchEnableLogging.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setEnableLogging(isChecked)
            if (isChecked) {
                Toast.makeText(requireContext(), getString(R.string.logging_enabled), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.logging_disabled), Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonDeleteLogFolder.setOnClickListener {
            if (viewModel.deleteLogFolder()) {
                Toast.makeText(requireContext(), getString(R.string.log_folder_deleted), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.log_folder_delete_error), Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonShareLastLog.setOnClickListener {
            val logFile = viewModel.getLastLogFile()
            if (logFile != null && logFile.exists()) {
                shareLogFile(logFile)
            } else {
                Toast.makeText(requireContext(), getString(R.string.no_logs_found), Toast.LENGTH_SHORT).show()
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
                putExtra(Intent.EXTRA_SUBJECT, "WiFi Frankenstein Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share log file"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error sharing log file", Toast.LENGTH_SHORT).show()
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
        requestLegacyPermissions.launch(arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ))
    }

    private fun setupButtons() {
        binding.buttonNotificationSettings.setOnClickListener {
            findNavController().navigate(R.id.action_nav_settings_to_notificationSettingsFragment)
        }
    }

    private fun setupExpandButtons() {
        binding.buttonExpandDbSettings.setOnClickListener {
            toggleExpansion(binding.layoutDbSettingsContent, binding.buttonExpandDbSettings)
        }
        binding.buttonExpandNotificationSettings.setOnClickListener {
            toggleExpansion(binding.layoutNotificationSettingsContent, binding.buttonExpandNotificationSettings)
        }
        binding.buttonExpandLoggingSettings.setOnClickListener {
            toggleExpansion(binding.layoutLoggingSettingsContent, binding.buttonExpandLoggingSettings)
        }
        binding.buttonExpandWifiMapSettings.setOnClickListener {
            toggleExpansion(binding.layoutWifiMapSettingsContent, binding.buttonExpandWifiMapSettings)
        }
        binding.buttonExpandAppSettings.setOnClickListener {
            toggleExpansion(binding.layoutAppSettingsContent, binding.buttonExpandAppSettings)
        }
        binding.buttonExpandThemeSettings.setOnClickListener {
            toggleExpansion(binding.layoutThemeSettingsContent, binding.buttonExpandThemeSettings)
        }
        binding.buttonExpandAPI3WiFiSettings.setOnClickListener {
            toggleExpansion(binding.layoutAPI3WiFiSettingsContent, binding.buttonExpandAPI3WiFiSettings)
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

    private fun setupMapSliders() {
        sliderClusterAggressiveness = binding.sliderClusterAggressiveness
        sliderMaxClusterSize = binding.sliderMaxClusterSize
        sliderMarkerVisibilityZoom = binding.sliderMarkerVisibilityZoom

        textViewMaxClusterSizeValue = binding.textViewMaxClusterSizeValue
        textViewMarkerVisibilityZoomValue = binding.textViewMarkerVisibilityZoomValue

        sliderClusterAggressiveness.value = viewModel.getClusterAggressiveness()
        sliderMaxClusterSize.value = viewModel.getMaxClusterSize().toFloat()
        sliderMarkerVisibilityZoom.value = viewModel.getMarkerVisibilityZoom()

        textViewMaxClusterSizeValue.text = viewModel.getMaxClusterSize().toString()
        textViewMarkerVisibilityZoomValue.text = getString(R.string.zoom_level_value, viewModel.getMarkerVisibilityZoom().toInt())

        sliderMarkerVisibilityZoom.valueFrom = 1f
        sliderMarkerVisibilityZoom.valueTo = 18f
        sliderMarkerVisibilityZoom.stepSize = 1f

        sliderClusterAggressiveness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setClusterAggressiveness(value)
            }
        }

        binding.switchForcePointSeparation.isChecked = viewModel.getForcePointSeparation()
        binding.switchForcePointSeparation.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setForcePointSeparation(isChecked)
        }

        binding.switchEnablePointLimits.isChecked = viewModel.getEnablePointLimits()
        binding.switchEnablePointLimits.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setEnablePointLimits(isChecked)
        }

        sliderMaxClusterSize.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val intValue = value.toInt()
                viewModel.setMaxClusterSize(intValue)
                textViewMaxClusterSizeValue.text = intValue.toString()
            }
        }

        sliderMarkerVisibilityZoom.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setMarkerVisibilityZoom(value)
                textViewMarkerVisibilityZoomValue.text = getString(R.string.zoom_level_value, value.toInt())
            }
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
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
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
                toggleExpansion(binding.layoutDeveloperSettingsContent, binding.buttonExpandDeveloperSettings)
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
    }

    private fun showAdvancedUploadWarning() {
        AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.developer_warning_title)
            .setMessage(R.string.developer_warning_message)
            .setPositiveButton(R.string.i_understand) { _, _ ->
                isDeveloperCardExpanded = true
                toggleExpansion(binding.layoutDeveloperSettingsContent, binding.buttonExpandDeveloperSettings)
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

    private fun setupClearCachedDatabasesButton() {
        binding.buttonClearCachedDatabases.setOnClickListener {
            viewModel.clearAllCachedDatabases()
            Toast.makeText(requireContext(), "Cached databases cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStorageAccessInfo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.buttonRequestStorageAccess.visibility = View.VISIBLE
            if (Environment.isExternalStorageManager()) {
                binding.textViewWarningStorageAccessInfo.visibility = View.GONE
                binding.textViewStorageAccessInfo.visibility = View.VISIBLE
                binding.textViewStorageAccessInfo.text = getString(R.string.storage_access_granted)
                binding.textViewStorageAccessInfo.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_500))
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
                binding.textViewStorageAccessInfo.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_500))
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
                else -> "purple"
            }
            viewModel.setColorTheme(colorTheme)
        }
    }

    private fun setupSwitches() {
        binding.switchFullCleanup.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setFullCleanup(isChecked)
        }

        binding.buttonResetThrottleWarning.setOnClickListener {
            val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("hide_throttle_warning", false).apply()
            Toast.makeText(requireContext(), getString(R.string.throttle_warning_reset), Toast.LENGTH_SHORT).show()
        }

        binding.switchPrioritizeNetworksWithData.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setPrioritizeNetworksWithData(isChecked)
        }

        binding.switchAutoScrollToNetworksWithData.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoScrollToNetworksWithData(isChecked)
        }

        binding.switchPrioritizeNetworksWithData.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setPrioritizeNetworksWithData(isChecked)
            binding.switchAutoScrollToNetworksWithData.isEnabled = isChecked
            if (!isChecked) {
                binding.switchAutoScrollToNetworksWithData.isChecked = false
                viewModel.setAutoScrollToNetworksWithData(false)
            }
        }

        binding.switchShowWipFeatures.setOnCheckedChangeListener { _, isChecked ->
            showWipFeaturesWarningDialog(isChecked)
        }
        viewModel.showWipFeatures.observe(viewLifecycleOwner) { isEnabled ->
            binding.switchShowWipFeatures.isChecked = isEnabled
        }

        binding.switchCheckUpdates.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setCheckUpdatesOnOpen(isChecked)
        }

        binding.switchScanOnStartup.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setScanOnStartup(isChecked)
        }

        binding.switchEnableRoot.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setEnableRoot(isChecked)
        }

        binding.switchExpandedSettings.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAlwaysExpandSettings(isChecked)
            updateSettingsExpansion(isChecked)
        }

        binding.switchMergeResults.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setMergeResults(isChecked)
        }
    }

    private fun showWipFeaturesWarningDialog(isChecked: Boolean) {
        if (isChecked) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.warning)
                .setMessage(R.string.wip_features_warning)
                .setPositiveButton(R.string.i_understand) { _, _ ->
                    viewModel.setShowWipFeatures(true)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    binding.switchShowWipFeatures.isChecked = false
                }
                .show()
        } else {
            viewModel.setShowWipFeatures(false)
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
            Toast.makeText(context, "API 3WiFi cache cleared", Toast.LENGTH_SHORT).show()
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

    private fun saveAPI3WiFiSettings() {
        val maxPoints = binding.editTextMaxPoints.text.toString().toIntOrNull() ?: 99

        if (maxPoints > 100) {
            showWarningDialog()
        }

        viewModel.setMaxPointsPerRequest(maxPoints)
        viewModel.setRequestDelay(binding.editTextRequestDelay.text.toString().toLongOrNull() ?: 1000)
        viewModel.setConnectTimeout(binding.editTextConnectTimeout.text.toString().toIntOrNull() ?: 5000)
        viewModel.setReadTimeout(binding.editTextReadTimeout.text.toString().toIntOrNull() ?: 5000)
        viewModel.setCacheResults(binding.switchCacheResults.isChecked)
        viewModel.setTryAlternativeUrl(binding.switchTryAlternativeUrl.isChecked)
        viewModel.setIgnoreSSLCertificate(binding.switchIgnoreSSLCertificate.isChecked)

        Toast.makeText(context, "API 3WiFi settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun showWarningDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.warning)
            .setMessage(R.string.max_points_warning)
            .setPositiveButton(R.string.ok, null)
            .show()
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

        Toast.makeText(context, "API 3WiFi settings reset to default", Toast.LENGTH_SHORT).show()
    }

    private fun updateSettingsExpansion(expand: Boolean) {
        binding.layoutDbSettingsContent.visibility = View.VISIBLE
        binding.layoutAppSettingsContent.visibility = View.VISIBLE
        binding.buttonExpandDbSettings.setIconResource(R.drawable.ic_expand_less)
        binding.buttonExpandAppSettings.setIconResource(R.drawable.ic_expand_less)

        val visibility = if (expand) View.VISIBLE else View.GONE
        binding.layoutLoggingSettingsContent.visibility = visibility
        binding.layoutThemeSettingsContent.visibility = visibility
        binding.layoutAPI3WiFiSettingsContent.visibility = visibility
        binding.layoutWifiMapSettingsContent.visibility = visibility

        val iconResource = if (expand) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        binding.buttonExpandLoggingSettings.setIconResource(iconResource)
        binding.buttonExpandThemeSettings.setIconResource(iconResource)
        binding.buttonExpandAPI3WiFiSettings.setIconResource(iconResource)
        binding.buttonExpandWifiMapSettings.setIconResource(iconResource)

        binding.layoutDeveloperSettingsContent.visibility = View.GONE
        binding.buttonExpandDeveloperSettings.setIconResource(R.drawable.ic_expand_more)

        binding.layoutNotificationSettingsContent.visibility = if (expand) View.VISIBLE else View.GONE
        binding.buttonExpandNotificationSettings.setIconResource(if (expand) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
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

        viewModel.enablePointLimits.observe(viewLifecycleOwner) { enabled ->
            binding.switchEnablePointLimits.isChecked = enabled
        }

        viewModel.enableLogging.observe(viewLifecycleOwner) { isEnabled ->
            binding.switchEnableLogging.isChecked = isEnabled
        }

        viewModel.prioritizeNetworksWithData.observe(viewLifecycleOwner) { isPrioritized ->
            binding.switchPrioritizeNetworksWithData.isChecked = isPrioritized
        }

        viewModel.autoScrollToNetworksWithData.observe(viewLifecycleOwner) { isEnabled ->
            binding.switchAutoScrollToNetworksWithData.isChecked = isEnabled
        }

        viewModel.prioritizeNetworksWithData.observe(viewLifecycleOwner) { isPrioritized ->
            binding.switchPrioritizeNetworksWithData.isChecked = isPrioritized
            binding.switchAutoScrollToNetworksWithData.isEnabled = isPrioritized
            if (!isPrioritized) {
                binding.switchAutoScrollToNetworksWithData.isChecked = false
            }
        }

        viewModel.forcePointSeparation.observe(viewLifecycleOwner) { value ->
            binding.switchForcePointSeparation.isChecked = value
        }

        viewModel.clusterAggressiveness.observe(viewLifecycleOwner) { value ->
            if (::sliderClusterAggressiveness.isInitialized && sliderClusterAggressiveness.value != value) {
                sliderClusterAggressiveness.value = value
            }
        }

        viewModel.markerVisibilityZoom.observe(viewLifecycleOwner) { value ->
            if (::sliderMarkerVisibilityZoom.isInitialized && sliderMarkerVisibilityZoom.value != value) {
                sliderMarkerVisibilityZoom.value = value
                if (::textViewMarkerVisibilityZoomValue.isInitialized) {
                    textViewMarkerVisibilityZoomValue.text = getString(R.string.zoom_level_value, value.toInt())
                }
            }
        }

        viewModel.includeAppIdentifier.observe(viewLifecycleOwner) { include ->
            binding.switchIncludeAppIdentifier.isChecked = include
        }

        viewModel.currentColorTheme.observe(viewLifecycleOwner) { colorTheme ->
            val radioButtonId = when (colorTheme) {
                "purple" -> R.id.radioButtonPurpleTheme
                "green" -> R.id.radioButtonGreenTheme
                "blue" -> R.id.radioButtonBlueTheme
                else -> R.id.radioButtonPurpleTheme
            }
            binding.radioGroupColorTheme.check(radioButtonId)
        }

        viewModel.fullCleanup.observe(viewLifecycleOwner) { isChecked ->
            binding.switchFullCleanup.isChecked = isChecked
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
        viewModel.alwaysExpandSettings.observe(viewLifecycleOwner) { alwaysExpand ->
            binding.switchExpandedSettings.isChecked = alwaysExpand
            updateSettingsExpansion(alwaysExpand)
        }
        viewModel.mergeResults.observe(viewLifecycleOwner) { mergeResults ->
            binding.switchMergeResults.isChecked = mergeResults
        }
    }

    companion object {
        private const val REQUEST_MANAGE_ALL_FILES_ACCESS_PERMISSION = 2
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}