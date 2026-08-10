package com.lsd.wififrankenstein.ui.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.lsd.wififrankenstein.databinding.FragmentNotificationSettingsBinding

class NotificationSettingsFragment : Fragment() {

    private var _binding: FragmentNotificationSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<NotificationSettingsViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSwitches()
        setupButtons()
        observeViewModel()
    }

    private fun setupSwitches() {
        binding.switchAppUpdates.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAppUpdatesEnabled(isChecked)
        }

        binding.switchDatabaseUpdates.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDatabaseUpdatesEnabled(isChecked)
        }

        binding.switchComponentUpdates.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setComponentUpdatesEnabled(isChecked)
        }

        binding.switchRecommendedDatabases.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setRecommendedDatabasesEnabled(isChecked)
        }

        binding.switchGeneralNotifications.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGeneralNotificationsEnabled(isChecked)
        }
    }

    private fun setupButtons() {
        binding.buttonSystemNotificationSettings.setOnClickListener {
            openSystemNotificationSettings()
        }
    }

    private fun openSystemNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${requireContext().packageName}".toUri()
            }
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${requireContext().packageName}".toUri()
            }
            startActivity(fallbackIntent)
        }
    }

    private fun observeViewModel() {
        viewModel.appUpdatesEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.switchAppUpdates.isChecked = enabled
        }

        viewModel.databaseUpdatesEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.switchDatabaseUpdates.isChecked = enabled
        }

        viewModel.componentUpdatesEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.switchComponentUpdates.isChecked = enabled
        }

        viewModel.recommendedDatabasesEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.switchRecommendedDatabases.isChecked = enabled
        }

        viewModel.generalNotificationsEnabled.observe(viewLifecycleOwner) { enabled ->
            binding.switchGeneralNotifications.isChecked = enabled
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}