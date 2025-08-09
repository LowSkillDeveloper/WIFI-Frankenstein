package com.lsd.wififrankenstein.ui.updates

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentUpdatesBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.util.AnimatedLoadingBar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File


private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

class UpdatesFragment : Fragment(R.layout.fragment_updates) {

    private var _binding: FragmentUpdatesBinding? = null
    private val binding get() = _binding!!

    private lateinit var updatesProgressBar: AnimatedLoadingBar

    private val viewModel: UpdatesViewModel by viewModels()
    private val dbSetupViewModel: DbSetupViewModel by activityViewModels()

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
        binding.buttonUpdateAll.setOnClickListener {
            if (viewModel.updateInfo.value.any { it.needsUpdate }) {
                if (checkNotificationPermission()) {
                    viewModel.updateAllFiles()
                } else {
                    showNotificationPermissionForBatchDialog()
                }
            } else {
                viewModel.checkUpdates()
            }
        }

        binding.buttonUpdateApp.setOnClickListener {
            viewModel.updateApp()
        }

        binding.buttonShowChangelog.setOnClickListener {
            viewModel.getChangelog()
        }
    }

    private fun showNotificationPermissionForBatchDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_batch_message)
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    pendingFileInfo = null
                    notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
                }
                .setNegativeButton(R.string.download_without_notifications) { _, _ ->
                    viewModel.updateAllFiles()
                }
                .setNeutralButton(R.string.cancel, null)
                .show()
        } else {
            viewModel.updateAllFiles()
        }
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
                            }
                        }
                    }

                    launch {
                        viewModel.updateInfo.collectLatest { updateInfoList ->
                            if (_binding != null) {
                                adapter.submitList(updateInfoList)
                                val anyUpdatesAvailable = updateInfoList.any { it.needsUpdate }
                                binding.buttonUpdateAll.visibility = View.VISIBLE
                                binding.buttonUpdateAll.text =
                                    if (anyUpdatesAvailable) getString(R.string.update_all) else getString(R.string.check_for_updates)
                                binding.textViewErrorMessage.visibility = View.GONE
                                binding.recyclerViewUpdates.visibility = View.VISIBLE
                            }
                        }
                    }

                    launch {
                        viewModel.errorMessage.collectLatest { errorMessage ->
                            errorMessage?.let {
                                binding.textViewErrorMessage.text = it
                                binding.textViewErrorMessage.visibility = View.VISIBLE
                                binding.buttonUpdateAll.text = getString(R.string.retry)
                            }
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
                                binding.textViewNewAppVersion.text = getString(R.string.app_up_to_date)
                                binding.buttonUpdateApp.visibility = View.GONE
                                binding.buttonShowChangelog.visibility = View.GONE
                            }
                            binding.textViewErrorMessage.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.errorMessage.collectLatest { errorMessage ->
                        updatesProgressBar.stopAnimation()
                        errorMessage?.let {
                            binding.textViewErrorMessage.text = it
                            binding.textViewErrorMessage.visibility = View.VISIBLE
                            binding.buttonUpdateAll.text = getString(R.string.retry)
                        }
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
                        binding.cardViewSmartLinkDb.visibility = if (updates.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
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
        AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.changelog)
            .setMessage(changelog)
            .setPositiveButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(requireContext())
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
        AlertDialog.Builder(requireContext())
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
                "${requireContext().packageName}.provider",
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
            requireContext().unregisterReceiver(downloadCompleteReceiver)
        } catch (e: Exception) {
            Log.e("UpdatesFragment", "Error unregistering receiver", e)
        }
        _binding = null
    }

    companion object {
        private const val REQUEST_INSTALL_PERMISSION = 1001
    }
}