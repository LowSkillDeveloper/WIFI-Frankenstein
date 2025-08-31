package com.lsd.wififrankenstein.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.databinding.FragmentChrootInstallBinding
import com.lsd.wififrankenstein.util.ChrootManager
import kotlinx.coroutines.launch

class ChrootInstallFragment : Fragment() {

    private var _binding: FragmentChrootInstallBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WelcomeViewModel by activityViewModels()
    private lateinit var chrootManager: ChrootManager
    private var installationCancelled = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChrootInstallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chrootManager = ChrootManager(requireContext())

        setupViews()
        startInstallation()
    }

    private fun setupViews() {
        val architecture = if (chrootManager.isArm64()) "ARM64" else "ARM"
        binding.textViewArchitecture.text = getString(R.string.installing_for_architecture, architecture)

        binding.buttonCancel.setOnClickListener {
            cancelInstallation()
        }
    }

    private fun startInstallation() {
        lifecycleScope.launch {
            binding.buttonCancel.isEnabled = true

            val success = chrootManager.downloadAndInstall(
                onProgress = { progress ->
                    activity?.runOnUiThread {
                        binding.progressBar.progress = progress
                        binding.textViewProgress.text = getString(R.string.progress_percent, progress)
                    }
                },
                onStatusUpdate = { status ->
                    activity?.runOnUiThread {
                        binding.textViewStatus.text = status
                    }
                }
            )

            if (!installationCancelled) {
                handleInstallationResult(success)
            }
        }
    }

    private fun handleInstallationResult(success: Boolean) {
        binding.buttonCancel.isEnabled = false

        if (success) {
            binding.textViewStatus.text = getString(R.string.chroot_installation_completed)
            binding.buttonContinue.visibility = View.VISIBLE
            binding.buttonContinue.setOnClickListener {
                (activity as? com.lsd.wififrankenstein.WelcomeActivity)?.navigateToNextFragment()
            }
        } else {
            binding.textViewStatus.text = getString(R.string.chroot_installation_failed)
            binding.buttonRetry.visibility = View.VISIBLE
            binding.buttonRetry.setOnClickListener {
                resetViews()
                startInstallation()
            }
        }
    }

    private fun cancelInstallation() {
        installationCancelled = true
        binding.textViewStatus.text = getString(R.string.installation_cancelled)
        binding.buttonCancel.isEnabled = false
        binding.buttonSkip.visibility = View.VISIBLE
        binding.buttonSkip.setOnClickListener {
            (activity as? com.lsd.wififrankenstein.WelcomeActivity)?.navigateToNextFragment()
        }
    }

    private fun resetViews() {
        installationCancelled = false
        binding.progressBar.progress = 0
        binding.textViewProgress.text = getString(R.string.progress_percent, 0)
        binding.buttonContinue.visibility = View.GONE
        binding.buttonRetry.visibility = View.GONE
        binding.buttonSkip.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = ChrootInstallFragment()
    }
}