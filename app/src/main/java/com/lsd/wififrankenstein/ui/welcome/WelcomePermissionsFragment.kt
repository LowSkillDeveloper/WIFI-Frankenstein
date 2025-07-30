package com.lsd.wififrankenstein.ui.welcome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.databinding.FragmentWelcomePermissionsBinding

class WelcomePermissionsFragment : Fragment() {

    private var _binding: FragmentWelcomePermissionsBinding? = null
    private val binding get() = _binding!!
    private val welcomeViewModel: WelcomeViewModel by activityViewModels()

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions.entries.all { it.value }
        updateLocationPermissionUI(locationGranted)
        welcomeViewModel.setLocationPermissionGranted(locationGranted)
        if (!locationGranted) {
            showLocationPermissionDeniedDialog()
        }
    }

    private val requestStorageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val hasStorage = hasStoragePermission()
        updateStoragePermissionUI(hasStorage)
        welcomeViewModel.setStoragePermissionGranted(hasStorage)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomePermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupPermissionCards()

        binding.buttonRequestLocationPermission.setOnClickListener {
            requestLocationPermission()
        }

        binding.buttonRequestStoragePermission.setOnClickListener {
            requestStoragePermission()
        }

        welcomeViewModel.locationPermissionGranted.observe(viewLifecycleOwner) { granted ->
            updateLocationPermissionUI(granted)
        }

        welcomeViewModel.storagePermissionGranted.observe(viewLifecycleOwner) { granted ->
            updateStoragePermissionUI(granted)
        }

        updateLocationPermissionUI(hasLocationPermission())
        updateStoragePermissionUI(hasStoragePermission())

        (activity as? WelcomeActivity)?.updateNavigationButtons(
            showPrev = true,
            showSkip = true,
            showNext = true
        )
    }

    private fun setupPermissionCards() {
        val locationNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        val storageNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

        if (!locationNeeded) {
            binding.cardViewLocationPermission.visibility = View.GONE
            welcomeViewModel.setLocationPermissionGranted(true)
        }

        if (!storageNeeded) {
            binding.cardViewStoragePermission.visibility = View.GONE
            welcomeViewModel.setStoragePermissionGranted(true)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            binding.textViewStorageTitle.text = getString(R.string.storage_permission_legacy)
            binding.textViewStorageDescription.text = getString(R.string.storage_permission_description_legacy)
        }

        if (!locationNeeded && !storageNeeded) {
            binding.textViewPermissionsDescription.text = getString(R.string.permissions_not_required_version)
            binding.textViewPermissionsFooter.text = getString(R.string.permissions_granted_automatically)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun hasLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        val fineLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (permissions.any {
                ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), it)
            }) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.location_permission_title)
                .setMessage(R.string.location_permission_rationale)
                .setPositiveButton(R.string.ok) { _, _ ->
                    requestLocationPermissionLauncher.launch(permissions)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            requestLocationPermissionLauncher.launch(permissions)
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${requireContext().packageName}".toUri()
                }
                requestStorageAccessLauncher.launch(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                requestStorageAccessLauncher.launch(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun showLocationPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.permission_denied)
            .setMessage(R.string.location_permission_denied_message)
            .setPositiveButton(R.string.request_again) { _, _ ->
                requestLocationPermission()
            }
            .setNegativeButton(R.string.continue_anyway, null)
            .show()
    }

    private fun showStoragePermissionDeniedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.permission_denied)
            .setMessage(R.string.storage_permission_denied_message)
            .setPositiveButton(R.string.request_again) { _, _ ->
                requestStoragePermission()
            }
            .setNegativeButton(R.string.continue_anyway, null)
            .show()
    }

    private fun updateLocationPermissionUI(granted: Boolean) {
        var finalGranted = granted
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            finalGranted = true
        }

        if (finalGranted) {
            binding.imageViewLocationStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imageViewLocationStatus.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.green_500)
            )
            binding.buttonRequestLocationPermission.text = getString(R.string.permission_granted)
            binding.buttonRequestLocationPermission.isEnabled = false
        } else {
            binding.imageViewLocationStatus.setImageResource(R.drawable.ic_error)
            binding.imageViewLocationStatus.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.error_red)
            )
            binding.buttonRequestLocationPermission.text = getString(R.string.request_permission)
            binding.buttonRequestLocationPermission.isEnabled = true
        }
    }

    private fun updateStoragePermissionUI(granted: Boolean) {
        var finalGranted = granted
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            finalGranted = true
        }

        if (finalGranted) {
            binding.imageViewStorageStatus.setImageResource(R.drawable.ic_check_circle)
            binding.imageViewStorageStatus.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.green_500)
            )
            binding.buttonRequestStoragePermission.text = getString(R.string.permission_granted)
            binding.buttonRequestStoragePermission.isEnabled = false
        } else {
            binding.imageViewStorageStatus.setImageResource(R.drawable.ic_error)
            binding.imageViewStorageStatus.setColorFilter(
                ContextCompat.getColor(requireContext(), R.color.error_red)
            )
            binding.buttonRequestStoragePermission.text = getString(R.string.request_permission)
            binding.buttonRequestStoragePermission.isEnabled = true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            STORAGE_PERMISSION_REQUEST_CODE -> {
                val granted = grantResults.isNotEmpty() &&
                        grantResults[0] == PackageManager.PERMISSION_GRANTED
                updateStoragePermissionUI(granted)
                welcomeViewModel.setStoragePermissionGranted(granted)

                if (!granted) {
                    showStoragePermissionDeniedDialog()
                }
            }
        }
    }

    companion object {
        private const val STORAGE_PERMISSION_REQUEST_CODE = 100

        fun newInstance() = WelcomePermissionsFragment()
    }
}