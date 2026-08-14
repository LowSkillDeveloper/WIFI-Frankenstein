package com.lsd.wififrankenstein.ui.updates

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts

import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentUpdatesBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.util.AnimatedLoadingBar
import com.lsd.wififrankenstein.util.ChrootType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.RootlessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

class UpdatesFragment : Fragment(R.layout.fragment_updates) {

    private var _binding: FragmentUpdatesBinding? = null
    private val binding get() = _binding!!

    private lateinit var updatesProgressBar: AnimatedLoadingBar

    private val viewModel: UpdatesViewModel by viewModels()
    private val dbSetupViewModel: DbSetupViewModel by lazy {
        DbSetupViewModel.getInstance(requireActivity().application)
    }
    private val settingsViewModel: com.lsd.wififrankenstein.ui.settings.SettingsViewModel by activityViewModels()

    private lateinit var adapter: UpdatesAdapter
    private lateinit var smartLinkDbAdapter: SmartLinkDbUpdateAdapter

    private var downloadId: Long = -1
    private var pendingFileInfo: FileUpdateInfo? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingFileInfo?.let { fileInfo ->
                viewModel.updateFile(fileInfo)
                pendingFileInfo = null
            } ?: run {
                viewModel.updateAllFiles()
            }
        } else {
            showNotificationPermissionDialog()
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showExitConfirmationDialog()
        }
    }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) {
                installUpdate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requireContext().registerReceiver(
                downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            ContextCompat.registerReceiver(
                requireContext(),
                downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.setDbSetupViewModel(dbSetupViewModel)
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentUpdatesBinding.bind(view)
        updatesProgressBar = binding.progressBarUpdatesCheck

        setupRecyclerViews()
        setupButtons()
        setupChrootManagement()
        observeRootSetting()
        observeViewModel()

        viewLifecycleOwner.lifecycleScope.launch {
            dbSetupViewModel.loadDbList()
            viewModel.checkUpdates()
            viewModel.checkSmartLinkDbUpdates()
        }
    }

    private fun setupRecyclerViews() {
        adapter = UpdatesAdapter(
            onUpdateClick = { fileInfo ->
                if (checkNotificationPermission()) {
                    viewModel.updateFile(fileInfo)
                } else {
                    requestNotificationPermission(fileInfo)
                }
            },
            onRevertClick = { fileInfo -> viewModel.revertFile(fileInfo) },
            onCancelClick = { fileInfo ->
                showCancelDownloadDialog(fileInfo.fileName) {
                    viewModel.cancelDownload(fileInfo.fileName)
                }
            }
        )
        binding.recyclerViewUpdates.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewUpdates.adapter = adapter

        smartLinkDbAdapter = SmartLinkDbUpdateAdapter { updateInfo ->
            viewModel.updateSmartLinkDb(updateInfo)
        }
        binding.recyclerViewSmartLinkDb.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSmartLinkDb.adapter = smartLinkDbAdapter
    }

    private fun setupButtons() {
        binding.buttonUpdateApp.setOnClickListener {
            viewModel.updateApp()
        }

        binding.buttonShowChangelog.setOnClickListener {
            viewModel.getChangelog()
        }

        binding.buttonDownloadRouterKeygen.setOnClickListener {
            viewModel.downloadRouterKeygenDic()
        }

        binding.buttonCancelRouterKeygen.setOnClickListener {
            showCancelDownloadDialog(UpdatesViewModel.ROUTER_KEYGEN_FILE_NAME) {
                viewModel.cancelDownload(UpdatesViewModel.ROUTER_KEYGEN_FILE_NAME)
            }
        }
    }

    private val chrootManager by lazy { com.lsd.wififrankenstein.util.ChrootManager(requireContext()) }

    private fun setupChrootManagement() {
        val chrootType = chrootManager.getChrootType()
        binding.cardViewChroot.visibility = View.VISIBLE

        binding.buttonCheckChrootUpdate.visibility = when (chrootType) {
            is com.lsd.wififrankenstein.util.ChrootType.Root -> View.VISIBLE
            is com.lsd.wififrankenstein.util.ChrootType.RootMissing -> View.VISIBLE
            com.lsd.wififrankenstein.util.ChrootType.None -> View.GONE
            is com.lsd.wififrankenstein.util.ChrootType.RootWithoutChroot -> View.GONE
            is com.lsd.wififrankenstein.util.ChrootType.Rootless -> View.GONE
        }

        when (chrootType) {
            is com.lsd.wififrankenstein.util.ChrootType.Root -> {
                val currentVersion = chrootManager.getCurrentVersion()
                binding.textViewChrootVersion.text =
                    getString(R.string.chroot_version, currentVersion ?: "unknown")
                binding.textViewChrootStatus.text = getString(R.string.chroot_installed)
                binding.buttonInstallChroot.visibility = View.GONE
                binding.buttonInstallChroot.text = getString(R.string.install_chroot)
                binding.buttonCheckChrootUpdate.isEnabled = true
            }

            is com.lsd.wififrankenstein.util.ChrootType.RootMissing -> {
                binding.textViewChrootVersion.text = getString(R.string.chroot_not_installed)
                binding.textViewChrootStatus.text =
                    getString(R.string.chroot_update_available_for_install)
                binding.buttonInstallChroot.visibility = View.VISIBLE
                binding.buttonInstallChroot.text = getString(R.string.install_chroot)
                binding.buttonCheckChrootUpdate.isEnabled = false
            }

            com.lsd.wififrankenstein.util.ChrootType.None -> {
                binding.textViewChrootVersion.text = getString(R.string.chroot_not_installed)
                binding.textViewChrootStatus.text = getString(R.string.root_required)
                binding.buttonInstallChroot.visibility = View.GONE
                binding.buttonInstallChroot.text = getString(R.string.install_chroot)
                binding.buttonCheckChrootUpdate.isEnabled = false
            }

            is com.lsd.wififrankenstein.util.ChrootType.RootWithoutChroot -> {
                binding.textViewChrootVersion.text = getString(R.string.chroot_not_installed)
                binding.textViewChrootStatus.text = getString(R.string.chroot_not_supported_proot)
                binding.buttonInstallChroot.visibility = View.VISIBLE
                binding.buttonInstallChroot.text = getString(R.string.install_rootless)
                binding.buttonCheckChrootUpdate.isEnabled = false
            }

            is com.lsd.wififrankenstein.util.ChrootType.Rootless -> {
                binding.textViewChrootVersion.text = getString(R.string.chroot_not_installed)
                binding.textViewChrootStatus.text = getString(R.string.rootless_mode)
                binding.buttonInstallChroot.visibility = View.VISIBLE
                binding.buttonInstallChroot.text = getString(R.string.install_rootless)
                binding.buttonCheckChrootUpdate.isEnabled = false
            }
        }

        binding.buttonCheckChrootUpdate.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                binding.textViewChrootStatus.text = getString(R.string.chroot_update_checking)
                val hasUpdate = chrootManager.checkForUpdates()
                if (hasUpdate) {
                    val chrootInfo = chrootManager.getChrootInfo()
                    binding.textViewChrootStatus.text =
                        getString(
                            R.string.chroot_update_available,
                            chrootInfo?.version ?: "unknown"
                        )
                    binding.buttonInstallChroot.visibility = View.VISIBLE
                } else {
                    binding.textViewChrootStatus.text = getString(R.string.chroot_latest)
                }
            }
        }

        binding.buttonInstallChroot.setOnClickListener {
            val ct = chrootManager.getChrootType()
            if (ct is ChrootType.Rootless || ct is ChrootType.RootWithoutChroot) {
                startRootlessInstall()
            } else {
                startChrootInstallation()
            }
        }
    }

    private fun startChrootInstallation() {
        if (!chrootManager.isArmArchitecture()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rs_unsupported_arch)
                .setMessage(
                    getString(
                        R.string.chroot_arch_warning,
                        chrootManager.getArchitecture().label
                    )
                )
                .setPositiveButton(R.string.continue_text) { _, _ -> internalStartChrootInstallation() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        if (chrootManager.isChrootInstalled()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.chroot_existing_dialog_title)
                .setMessage(R.string.chroot_existing_dialog_message)
                .setPositiveButton(R.string.chroot_use_existing) { _, _ ->
                    binding.textViewChrootStatus.text = getString(R.string.chroot_already_installed)
                    setupChrootManagement()
                }
                .setNegativeButton(R.string.chroot_clean_install) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        chrootManager.uninstall()
                        withContext(Dispatchers.Main) {
                            internalStartChrootInstallation()
                        }
                    }
                }
                .show()
            return
        }

        internalStartChrootInstallation()
    }

    private fun internalStartChrootInstallation() {
        binding.progressBarUpdatesCheck.visibility = View.VISIBLE
        binding.buttonInstallChroot.isEnabled = false
        binding.buttonCheckChrootUpdate.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val success = try {
                chrootManager.downloadAndInstall(
                    onProgress = { progress ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            binding.textViewChrootStatus.text =
                                "${getString(R.string.chroot_installing)} $progress%"
                        }
                    },
                    onStatusUpdate = { status ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            binding.textViewChrootStatus.text = status
                        }
                    }
                )
            } catch (e: Exception) {
                false
            }

            if (_binding == null) return@launch
            binding.progressBarUpdatesCheck.visibility = View.GONE
            binding.buttonInstallChroot.isEnabled = true
            binding.buttonCheckChrootUpdate.isEnabled = true

            if (success) {
                binding.textViewChrootStatus.text = getString(R.string.chroot_installed_success)
            } else {

            }
            setupChrootManagement()
        }
    }

    private fun startRootlessInstall() {
        if (isMobileNetworkForRootless()) {
            showMobileWarningForRootless()
            return
        }
        val rootlessManager = RootlessManager(requireContext())
        if (!rootlessManager.isSupportedArchitecture()) {
            com.lsd.wififrankenstein.util.Log.e(
                "UpdatesFragment",
                "Rootless requires arm64 or x86_64"
            )
            return
        }
        binding.textViewChrootStatus.text = getString(R.string.rootless_setup_starting)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val chrootInfo = chrootManager.getChrootInfo()
            val archive = chrootInfo?.let {
                if (chrootManager.isAarch64()) it.aarch64 else it.armhf
            }
            if (archive == null) {
                withContext(Dispatchers.Main) {
                    binding.textViewChrootStatus.text = getString(R.string.rootless_failed_rootfs_url)
                }
                return@launch
            }
            val success = rootlessManager.setupRootfs(
                onProgress = { progress ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        binding.textViewChrootStatus.text =
                            getString(R.string.rootless_downloading, progress)
                    }
                },
                onStatusUpdate = { status ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        binding.textViewChrootStatus.text = status
                    }
                },
                downloadUrl = archive.download_url,
                onDiagnosticUpdate = { name, icon, result ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        binding.textViewChrootStatus.text =
                            getString(R.string.rootless_diagnostic_line, icon, name, result)
                    }
                }
            )
            withContext(Dispatchers.Main) {
                if (success) {
                    binding.textViewChrootStatus.text = getString(R.string.rootless_setup_completed)
                }
                setupChrootManagement()
            }
        }
    }

    private fun isMobileNetworkForRootless(): Boolean {
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

    private fun showMobileWarningForRootless() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.warning)
            .setMessage(getString(R.string.rootless_mobile_warning_message))
            .setPositiveButton(R.string.continue_text) { _, _ -> startRootlessInstall() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    launch {
                        viewModel.isLoading.collectLatest { isLoading ->
                            if (isLoading) {
                                updatesProgressBar.startAnimation()
                            } else {
                                updatesProgressBar.stopAnimation()
                                updateAllUpToDateState()
                            }
                        }
                    }

                    launch {
                        viewModel.updateInfo.collectLatest { updateInfoList ->
                            if (_binding != null) {
                                adapter.submitList(updateInfoList)
                                binding.textViewErrorMessage.visibility = View.GONE
                                binding.recyclerViewUpdates.visibility = View.VISIBLE
                                updateAllUpToDateState()
                            }
                        }
                    }

                    launch {
                        viewModel.errorMessage.collectLatest { errorMessage ->
                            errorMessage?.let {
                                binding.textViewErrorMessage.text = it
                                binding.textViewErrorMessage.visibility = View.VISIBLE
                            }
                            updateAllUpToDateState()
                        }
                    }
                }

                launch {
                    viewModel.appUpdateInfo.collectLatest { appInfo ->
                        appInfo?.let {
                            binding.textViewAppVersion.text =
                                getString(R.string.current_version, it.currentVersion)
                            val updateAvailable = it.currentVersion != it.newVersion
                            if (updateAvailable) {
                                binding.textViewNewAppVersion.text =
                                    getString(R.string.new_version_available, it.newVersion)
                                binding.buttonUpdateApp.visibility = View.VISIBLE
                                binding.buttonShowChangelog.visibility = View.VISIBLE
                            } else {
                                binding.textViewNewAppVersion.text =
                                    getString(R.string.app_up_to_date)
                                binding.buttonUpdateApp.visibility = View.GONE
                                binding.buttonShowChangelog.visibility = View.GONE
                            }
                            binding.textViewErrorMessage.visibility = View.GONE
                            updateAllUpToDateState()
                        }
                    }
                }

                launch {
                    viewModel.errorMessage.collectLatest { errorMessage ->
                        updatesProgressBar.stopAnimation()
                        errorMessage?.let {
                            binding.textViewErrorMessage.text = it
                            binding.textViewErrorMessage.visibility = View.VISIBLE
                        }
                        updateAllUpToDateState()
                    }
                }

                launch {
                    viewModel.changelog.collectLatest { changelog ->
                        changelog?.let { showChangelogDialog(it) }
                    }
                }

                launch {
                    viewModel.appDownloadId.collectLatest { id ->
                        downloadId = id
                    }
                }

                launch {
                    viewModel.openUrlInBrowser.collectLatest { url ->
                        url?.let {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                            startActivity(intent)
                        }
                    }
                }

                launch {
                    viewModel.fileUpdateProgress.collectLatest { progressMap ->
                        adapter.updateProgress(progressMap)
                    }
                }

                launch {
                    viewModel.activeDownloads.collectLatest { activeDownloads ->
                        adapter.updateActiveDownloads(activeDownloads)
                    }
                }

                launch {
                    viewModel.hasActiveDownloads.collectLatest { hasActiveDownloads ->
                        onBackPressedCallback.isEnabled = hasActiveDownloads
                    }
                }

                launch {
                    viewModel.appUpdateProgress.collectLatest { progress ->
                        binding.progressBarAppUpdate.progress = progress
                        binding.progressBarAppUpdate.visibility =
                            if (progress in 0..99) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.smartLinkDbUpdates.collectLatest { updates ->
                        Log.d("UpdatesFragment", "SmartLinkDb updates: ${updates.size}")
                        smartLinkDbAdapter.submitList(updates)
                        binding.cardViewSmartLinkDb.visibility =
                            if (updates.isNotEmpty()) View.VISIBLE else View.GONE
                        updateAllUpToDateState()
                    }
                }

                launch {
                    viewModel.routerKeygenInfo.collectLatest { info ->
                        info?.let {
                            if (it.installed) {
                                binding.cardViewRouterKeygen.visibility = View.GONE
                            } else if (it.isDownloading) {
                                binding.cardViewRouterKeygen.visibility = View.VISIBLE
                                binding.textViewRouterKeygenStatus.text =
                                    getString(
                                        R.string.router_keygen_installing,
                                        it.downloadProgress
                                    )
                                binding.buttonDownloadRouterKeygen.visibility = View.GONE
                                binding.buttonCancelRouterKeygen.visibility = View.VISIBLE
                                binding.progressBarRouterKeygen.visibility = View.VISIBLE
                                binding.progressBarRouterKeygen.progress = it.downloadProgress
                            } else {
                                binding.cardViewRouterKeygen.visibility = View.VISIBLE
                                binding.textViewRouterKeygenStatus.text =
                                    getString(
                                        R.string.router_keygen_required,
                                        getString(R.string.router_keygen_download_size)
                                    )
                                binding.buttonDownloadRouterKeygen.visibility = View.VISIBLE
                                binding.buttonCancelRouterKeygen.visibility = View.GONE
                                binding.progressBarRouterKeygen.visibility = View.GONE
                            }
                        } ?: run {
                            binding.cardViewRouterKeygen.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun observeRootSetting() {
        val rootEnabled = settingsViewModel.enableRoot.value == true
        binding.cardViewChroot.visibility = if (rootEnabled) View.VISIBLE else View.GONE

        settingsViewModel.enableRoot.observe(viewLifecycleOwner) { enabled ->
            binding.cardViewChroot.visibility = if (enabled) View.VISIBLE else View.GONE
            setupChrootManagement()
        }
    }

    private fun updateAllUpToDateState() {
        val appInfo = viewModel.appUpdateInfo.value
        val filesUpToDate =
            viewModel.updateInfo.value.isEmpty() || viewModel.updateInfo.value.none { it.needsUpdate }
        val smartLinkUpToDate = viewModel.smartLinkDbUpdates.value.none { it.needsUpdate }

        val allUpToDate = !viewModel.isLoading.value &&
                viewModel.errorMessage.value == null &&
                appInfo != null &&
                appInfo.currentVersion == appInfo.newVersion &&
                filesUpToDate &&
                smartLinkUpToDate

        binding.textViewAllUpToDate.visibility = if (allUpToDate) View.VISIBLE else View.GONE
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                POST_NOTIFICATIONS_PERMISSION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestNotificationPermission(fileInfo: FileUpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (shouldShowRequestPermissionRationale(POST_NOTIFICATIONS_PERMISSION)) {
                showNotificationPermissionRationaleDialog(fileInfo)
            } else {
                pendingFileInfo = fileInfo
                notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
            }
        } else {
            viewModel.updateFile(fileInfo)
        }
    }

    private fun showNotificationPermissionRationaleDialog(fileInfo: FileUpdateInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.notification_permission_message)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                pendingFileInfo = fileInfo
                notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
            }
            .setNegativeButton(R.string.download_without_notifications) { _, _ ->
                viewModel.updateFile(fileInfo)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun showNotificationPermissionDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.notification_permission_denied_title)
            .setMessage(R.string.notification_permission_denied_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(R.string.download_without_notifications) { _, _ ->
                pendingFileInfo?.let { fileInfo ->
                    viewModel.updateFile(fileInfo)
                    pendingFileInfo = null
                } ?: run {
                    viewModel.updateAllFiles()
                }
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }

    private fun showChangelogDialog(changelog: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.changelog)
            .setMessage(changelog)
            .setPositiveButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showExitConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.exit_updates_title)
            .setMessage(R.string.exit_updates_message)
            .setPositiveButton(R.string.exit_anyway) { _, _ ->
                onBackPressedCallback.isEnabled = false
                requireActivity().onBackPressed()
            }
            .setNegativeButton(R.string.stay_here, null)
            .setNeutralButton(R.string.cancel_all_downloads) { _, _ ->
                viewModel.cancelAllDownloads()
                onBackPressedCallback.isEnabled = false
                requireActivity().onBackPressed()
            }
            .show()
    }

    private fun showCancelDownloadDialog(fileName: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cancel_download_title)
            .setMessage(getString(R.string.cancel_download_message, fileName))
            .setPositiveButton(R.string.yes) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun installUpdate() {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "app-update.apk"
        )
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
        } else {
            Uri.fromFile(file)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !requireContext().packageManager.canRequestPackageInstalls()) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:${requireContext().packageName}")),
                REQUEST_INSTALL_PERMISSION
            )
        } else {
            startActivity(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL_PERMISSION && resultCode == android.app.Activity.RESULT_OK) {
            installUpdate()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            context?.unregisterReceiver(downloadCompleteReceiver)
        } catch (e: Exception) {
            Log.e("UpdatesFragment", "Error unregistering receiver", e)
        }
        _binding = null
    }

    companion object {
        private const val REQUEST_INSTALL_PERMISSION = 1001
    }
}