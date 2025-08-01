package com.lsd.wififrankenstein.ui.welcome

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentWelcomeUpdatesBinding
import com.lsd.wififrankenstein.ui.updates.SmartLinkDbUpdateAdapter
import com.lsd.wififrankenstein.ui.updates.UpdateChecker
import com.lsd.wififrankenstein.ui.updates.UpdatesAdapter
import com.lsd.wififrankenstein.ui.updates.UpdatesViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WelcomeUpdatesFragment : Fragment() {

    private var _binding: FragmentWelcomeUpdatesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: UpdatesViewModel by activityViewModels()
    private lateinit var updatesAdapter: UpdatesAdapter
    private lateinit var smartLinkDbAdapter: SmartLinkDbUpdateAdapter
    private var isUpdating = false
    private val handler = Handler(Looper.getMainLooper())
    private var downloadId: Long = -1

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

        setupRecyclerView()
        setupAppUpdateButtons()
        checkForUpdates()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        updatesAdapter = UpdatesAdapter(
            onUpdateClick = { fileInfo ->
                viewModel.updateFile(fileInfo)
                handler.postDelayed({
                    viewModel.checkUpdates()
                }, 1000)
            },
            onRevertClick = { fileInfo ->
                viewModel.revertFile(fileInfo)
                handler.postDelayed({
                    viewModel.checkUpdates()
                }, 1000)
            }
        )
        binding.recyclerViewUpdates.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewUpdates.adapter = updatesAdapter

        smartLinkDbAdapter = SmartLinkDbUpdateAdapter { updateInfo ->
            viewModel.updateSmartLinkDb(updateInfo)

            handler.postDelayed({
                viewModel.checkSmartLinkDbUpdates()
            }, 1000)
        }
        binding.recyclerViewSmartLinkDb.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSmartLinkDb.adapter = smartLinkDbAdapter
    }

    private fun setupAppUpdateButtons() {
        binding.buttonUpdateApp.setOnClickListener {
            viewModel.updateApp()
        }

        binding.buttonShowChangelog.setOnClickListener {
            viewModel.getChangelog()
        }

        binding.buttonUpdateAll.setOnClickListener {
            if (viewModel.updateInfo.value.any { it.needsUpdate }) {
                viewModel.updateAllFiles()
                handler.postDelayed({
                    viewModel.checkUpdates()
                }, 1000)
            } else {
                viewModel.checkUpdates()
            }
        }
    }

    private fun checkForUpdates() {
        if (isUpdating) return

        isUpdating = true
        lifecycleScope.launch {
            val updateChecker = UpdateChecker(requireContext())
            updateChecker.checkForUpdates().collect { status ->
                if (!isAdded) return@collect

                binding.progressBarUpdates.isIndeterminate = false
                binding.progressBarUpdates.progress = 100
                isUpdating = false

                if (status.fileUpdates.any { it.needsUpdate }) {
                    binding.textViewUpdateStatus.text = getString(R.string.updates_available)
                    updatesAdapter.submitList(status.fileUpdates)
                    binding.recyclerViewUpdates.visibility = View.VISIBLE
                } else {
                    binding.textViewUpdateStatus.text = getString(R.string.everything_up_to_date)
                    binding.recyclerViewUpdates.visibility = View.GONE
                }

                status.appUpdate?.let { appUpdate ->
                    binding.textViewAppVersion.text = getString(R.string.current_version, appUpdate.currentVersion)

                    if (appUpdate.currentVersion != appUpdate.newVersion) {
                        binding.textViewNewAppVersion.text = getString(R.string.new_version_available, appUpdate.newVersion)
                        binding.textViewNewAppVersion.visibility = View.VISIBLE
                        binding.buttonUpdateApp.visibility = View.VISIBLE
                        binding.buttonShowChangelog.visibility = View.VISIBLE
                    } else {
                        binding.textViewNewAppVersion.text = getString(R.string.app_up_to_date)
                        binding.textViewNewAppVersion.visibility = View.VISIBLE
                        binding.buttonUpdateApp.visibility = View.GONE
                        binding.buttonShowChangelog.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.updateInfo.collectLatest { updateInfoList ->
                        updatesAdapter.submitList(updateInfoList)
                        val anyUpdatesAvailable = updateInfoList.any { it.needsUpdate }
                        if (true) {
                            binding.buttonUpdateAll.visibility = View.VISIBLE
                            binding.buttonUpdateAll.text =
                                if (anyUpdatesAvailable) getString(R.string.update_all) else getString(R.string.check_for_updates)
                        }
                        if (true) {
                            binding.textViewErrorMessage.visibility = View.GONE
                        }
                        binding.recyclerViewUpdates.visibility = View.VISIBLE
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
                            if (true) {
                                binding.textViewErrorMessage.visibility = View.GONE
                            }
                        }
                    }
                }

                launch {
                    viewModel.errorMessage.collectLatest { errorMessage ->
                        errorMessage?.let {
                            if (true) {
                                binding.textViewErrorMessage.text = it
                                binding.textViewErrorMessage.visibility = View.VISIBLE
                            }
                            if (true) {
                                binding.buttonUpdateAll.text = getString(R.string.retry)
                            }
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
                            val intent = Intent(Intent.ACTION_VIEW, it.toUri())
                            startActivity(intent)
                        }
                    }
                }

                launch {
                    viewModel.fileUpdateProgress.collectLatest { progressMap ->
                        updatesAdapter.updateProgress(progressMap)
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
                        if (true) {
                            binding.cardViewSmartLinkDb.visibility = if (updates.isNotEmpty()) View.VISIBLE else View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun showChangelogDialog(changelog: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.changelog)
            .setMessage(changelog)
            .setPositiveButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }

    companion object {
        fun newInstance() = WelcomeUpdatesFragment()
    }
}