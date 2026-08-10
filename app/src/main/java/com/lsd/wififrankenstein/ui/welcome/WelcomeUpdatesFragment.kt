package com.lsd.wififrankenstein.ui.welcome

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.databinding.FragmentWelcomeUpdatesBinding
import com.lsd.wififrankenstein.ui.updates.FileUpdateInfo
import com.lsd.wififrankenstein.ui.updates.SmartLinkDbUpdateAdapter
import com.lsd.wififrankenstein.ui.updates.UpdatesAdapter
import com.lsd.wififrankenstein.ui.updates.UpdatesViewModel
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WelcomeUpdatesFragment : Fragment() {

    private var _binding: FragmentWelcomeUpdatesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: UpdatesViewModel by activityViewModels()
    private lateinit var updatesAdapter: UpdatesAdapter
    private lateinit var smartLinkDbAdapter: SmartLinkDbUpdateAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeUpdatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? WelcomeActivity)?.setBottomHint(null)
        setupRecyclerView()
        setupRouterKeygenButtons()
        observeViewModel()

        viewModel.checkUpdates()
        viewModel.checkSmartLinkDbUpdates()

        binding.buttonUpdateAll.setOnClickListener {
            viewModel.updateAllFiles()
        }
    }

    private fun setupRouterKeygenButtons() {
        binding.buttonDownloadRouterKeygen.setOnClickListener {
            viewModel.downloadRouterKeygenDic()
        }
        binding.buttonCancelRouterKeygen.setOnClickListener {
            viewModel.cancelDownload(UpdatesViewModel.ROUTER_KEYGEN_FILE_NAME)
        }
    }

    private fun setupRecyclerView() {

        updatesAdapter = UpdatesAdapter(
            onUpdateClick = { fileInfo ->
                if (checkNotificationPermission()) {
                    viewModel.updateFile(fileInfo)
                    scheduleUpdateCheck()
                } else {
                    requestNotificationPermission(fileInfo)
                }
            },
            onRevertClick = { fileInfo ->
                viewModel.revertFile(fileInfo)
                scheduleUpdateCheck()
            },
            onCancelClick = { fileInfo ->
                viewModel.cancelDownload(fileInfo.fileName)
            }
        )
        binding.recyclerViewUpdates.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewUpdates.adapter = updatesAdapter

        smartLinkDbAdapter = SmartLinkDbUpdateAdapter { updateInfo ->
            viewModel.updateSmartLinkDb(updateInfo)

            scheduleSmartLinkDbUpdateCheck()
        }
        binding.recyclerViewSmartLinkDb.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSmartLinkDb.adapter = smartLinkDbAdapter
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestNotificationPermission(fileInfo: FileUpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                showNotificationPermissionRationaleDialog(fileInfo)
            } else {
                pendingFileInfo = fileInfo
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            viewModel.updateFile(fileInfo)
            scheduleUpdateCheck()
        }
    }

    private var pendingFileInfo: FileUpdateInfo? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingFileInfo?.let { fileInfo ->
                viewModel.updateFile(fileInfo)
                pendingFileInfo = null
                scheduleUpdateCheck()
            } ?: run {
                viewModel.updateAllFiles()
                scheduleUpdateCheck()
            }
        } else {
            showNotificationPermissionDialog()
        }
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
                    scheduleUpdateCheck()
                } ?: run {
                    viewModel.updateAllFiles()
                    scheduleUpdateCheck()
                }
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", requireContext().packageName, null)
        }
        startActivity(intent)
    }

    private fun showNotificationPermissionRationaleDialog(fileInfo: FileUpdateInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.notification_permission_title)
            .setMessage(R.string.notification_permission_message)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                pendingFileInfo = fileInfo
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(R.string.download_without_notifications) { _, _ ->
                viewModel.updateFile(fileInfo)
                scheduleUpdateCheck()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun scheduleUpdateCheck() {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(1000)
            viewModel.checkUpdates()
        }
    }

    private fun scheduleSmartLinkDbUpdateCheck() {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(1000)
            viewModel.checkSmartLinkDbUpdates()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.updateInfo.collectLatest { updateInfoList ->
                        updatesAdapter.submitList(updateInfoList)
                        val anyUpdatesAvailable = updateInfoList.any { it.needsUpdate }
                        val hasUninstalled =
                            updateInfoList.any { it.localVersion == "0.0" || it.localVersion == "0" }
                        binding.buttonUpdateAll.text =
                            if (anyUpdatesAvailable) getString(R.string.update_all) else getString(R.string.check_for_updates)
                        binding.recyclerViewUpdates.visibility =
                            if (anyUpdatesAvailable || hasUninstalled) View.VISIBLE else View.GONE
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

                launch {
                    viewModel.fileUpdateProgress.collectLatest { progressMap ->
                        updatesAdapter.updateProgress(progressMap)
                    }
                }

                launch {
                    viewModel.smartLinkDbUpdates.collectLatest { updates ->
                        Log.d("UpdatesFragment", "SmartLinkDb updates: ${updates.size}")
                        smartLinkDbAdapter.submitList(updates)
                        binding.cardViewSmartLinkDb.visibility =
                            if (updates.isNotEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.activeDownloads.collectLatest { activeDownloads ->
                        updatesAdapter.updateActiveDownloads(activeDownloads)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = WelcomeUpdatesFragment()
    }
}
