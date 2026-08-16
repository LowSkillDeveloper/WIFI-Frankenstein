package com.lsd.wififrankenstein.ui.welcome

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.databinding.FragmentWelcomeVersionCheckBinding
import com.lsd.wififrankenstein.ui.updates.AppUpdateInfo
import com.lsd.wififrankenstein.ui.updates.UpdateChecker
import com.lsd.wififrankenstein.ui.updates.UpdatesViewModel
import com.lsd.wififrankenstein.util.AppApkInstaller
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class WelcomeVersionCheckFragment : Fragment() {

    private var _binding: FragmentWelcomeVersionCheckBinding? = null
    private val binding get() = _binding!!
    private val viewModel: UpdatesViewModel by activityViewModels()
    private var hasChecked = false
    private var shouldInstallAfterDownload = false

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!AppApkInstaller.needsUnknownSourcesPermission(requireContext())) {
            startDownloadAndInstall()
        } else {
            showManualInstallMessage()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeVersionCheckBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? WelcomeActivity)?.setBottomHint(null)
        setupButtons()
        observeViewModel()
    }

    fun checkVersion() {
        if (hasChecked) return
        hasChecked = true

        lifecycleScope.launch {
            val updateChecker = UpdateChecker(requireContext())

            val status = withTimeoutOrNull(8_000L) {
                updateChecker.checkForUpdates().first()
            }

            if (status == null || status.appUpdate == null) {
                proceedToNext()
            } else {
                showOutdatedWarning(status.appUpdate)
            }
        }
    }

    private fun showOutdatedWarning(appUpdate: AppUpdateInfo) {
        viewModel.setAppUpdateInfo(appUpdate)
        binding.textViewTitle.text = getString(R.string.updates_available)
        binding.progressBar.visibility = View.GONE
        binding.textViewStatus.text = getString(R.string.version_check_old_install_message)
        binding.cardViewVersionInfo.visibility = View.VISIBLE
        binding.textViewVersionInfo.text = getString(
            R.string.version_check_outdated_message,
            appUpdate.currentVersion, appUpdate.newVersion
        )
        binding.buttonInstallUpdate.visibility = View.VISIBLE
        binding.buttonDownloadApk.visibility = View.VISIBLE
        binding.buttonContinueAnyway.visibility = View.VISIBLE
    }

    private fun proceedToNext() {
        (activity as? WelcomeActivity)?.navigateToNextFragment()
    }

    private fun setupButtons() {
        binding.buttonInstallUpdate.setOnClickListener {
            if (AppApkInstaller.needsUnknownSourcesPermission(requireContext())) {
                showUnknownSourcesRationaleDialog()
            } else {
                startDownloadAndInstall()
            }
        }

        binding.buttonDownloadApk.setOnClickListener {
            startDownloadOnly()
        }

        binding.buttonContinueAnyway.setOnClickListener {
            proceedToNext()
        }
    }

    private fun showUnknownSourcesRationaleDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.unknown_sources_permission_title)
            .setMessage(R.string.unknown_sources_permission_message)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${requireContext().packageName}".toUri()
                )
                installPermissionLauncher.launch(intent)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                showManualInstallMessage()
            }
            .show()
    }

    private fun showManualInstallMessage() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.unknown_sources_permission_title)
            .setMessage(R.string.install_manual_download_message)
            .setPositiveButton(R.string.download_apk) { _, _ ->
                startDownloadOnly()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDownloadAndInstall() {
        shouldInstallAfterDownload = true
        viewModel.downloadAppApk()
    }

    private fun startDownloadOnly() {
        shouldInstallAfterDownload = false
        viewModel.downloadAppApk()
    }

    private fun showApkDownloadedMessage() {
        if (_binding == null) return
        Snackbar.make(
            binding.root,
            getString(R.string.apk_downloaded_to_downloads),
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                viewModel.apkDownloadedFile.collectLatest { file ->
                    if (file != null) {
                        viewModel.consumeApkDownloadedFile()
                        if (shouldInstallAfterDownload) {
                            AppApkInstaller.installApk(requireContext(), file)
                        } else {
                            showApkDownloadedMessage()
                        }
                    }
                }
            }

            launch {
                viewModel.openUrlInBrowser.collectLatest { url ->
                    url?.let {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, it.toUri()))
                        } catch (e: Exception) {
                            Log.e("WelcomeVersionCheck", "Failed to open download URL", e)
                        }
                    }
                }
            }

            launch {
                viewModel.errorMessage.collectLatest { errorMessage ->
                    errorMessage?.let {
                        if (_binding != null) {
                            MaterialAlertDialogBuilder(requireContext())
                                .setMessage(it)
                                .setPositiveButton(R.string.ok, null)
                                .show()
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
        fun newInstance() = WelcomeVersionCheckFragment()
    }
}
