package com.lsd.wififrankenstein.ui.welcome

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.databinding.FragmentWelcomeVersionCheckBinding
import com.lsd.wififrankenstein.ui.updates.AppUpdateInfo
import com.lsd.wififrankenstein.ui.updates.UpdateChecker
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class WelcomeVersionCheckFragment : Fragment() {

    private var _binding: FragmentWelcomeVersionCheckBinding? = null
    private val binding get() = _binding!!
    private var lastAppUpdate: AppUpdateInfo? = null
    private var hasChecked = false

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
        lastAppUpdate = appUpdate
        binding.textViewTitle.text = getString(R.string.updates_available)
        binding.progressBar.visibility = View.GONE
        binding.textViewStatus.text = getString(R.string.updates_available)
        binding.cardViewVersionInfo.visibility = View.VISIBLE
        binding.textViewVersionInfo.text = getString(
            R.string.version_check_outdated_message,
            appUpdate.currentVersion, appUpdate.newVersion
        )
        binding.buttonDownload.visibility = View.VISIBLE
        binding.buttonContinueAnyway.visibility = View.VISIBLE
    }

    private fun proceedToNext() {
        (activity as? WelcomeActivity)?.navigateToNextFragment()
    }

    private fun setupButtons() {
        binding.buttonDownload.setOnClickListener {
            lastAppUpdate?.let { appUpdate ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, appUpdate.downloadUrl.toUri())
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("WelcomeVersionCheck", "Failed to open download URL", e)
                }
            }
        }

        binding.buttonContinueAnyway.setOnClickListener {
            proceedToNext()
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
