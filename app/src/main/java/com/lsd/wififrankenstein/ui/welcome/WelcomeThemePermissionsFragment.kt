package com.lsd.wififrankenstein.ui.welcome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.content.res.AppCompatResources
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.databinding.FragmentWelcomeThemePermissionsBinding
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel

class WelcomeThemePermissionsFragment : Fragment() {

    private var _binding: FragmentWelcomeThemePermissionsBinding? = null
    private val binding get() = _binding!!
    private val settingsViewModel: SettingsViewModel by activityViewModels()
    private val welcomeViewModel: WelcomeViewModel by activityViewModels()
    private var isSequentialRequest = false

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        updateLocationSwitch(granted)
        welcomeViewModel.setLocationPermissionGranted(granted)
        if (isSequentialRequest) {
            if (granted) requestNextPermission() else showLocationDeniedDialog()
        } else if (!granted) {
            showLocationDeniedDialog()
        }
    }

    private val requestStorageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = hasStoragePermission()
        updateStorageSwitch(granted)
        welcomeViewModel.setStoragePermissionGranted(granted)
        if (isSequentialRequest) {
            if (granted) requestNextPermission() else showStorageDeniedDialog()
        }
    }

    private val requestLegacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateStorageSwitch(granted)
        welcomeViewModel.setStoragePermissionGranted(granted)
        if (isSequentialRequest) {
            if (granted) requestNextPermission() else showStorageDeniedDialog()
        } else if (!granted) {
            showStorageDeniedDialog()
        }
    }

    private val requestNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateNotificationSwitch(granted)
        welcomeViewModel.setNotificationPermissionGranted(granted)
        if (isSequentialRequest) {
            if (granted) requestNextPermission() else showNotificationDeniedDialog()
        } else if (!granted) {
            showNotificationDeniedDialog()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeThemePermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? WelcomeActivity)?.setBottomHint(null)
        setupTheme()
        setupPermissions()
        restoreThemeState()
    }

    private fun setupTheme() {
        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.radioButtonLightTheme -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioButtonDarkTheme -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            settingsViewModel.setTheme(theme)
            welcomeViewModel.setSelectedTheme(theme)
        }

        binding.radioGroupColorTheme.setOnCheckedChangeListener { _, checkedId ->
            val colorTheme = when (checkedId) {
                R.id.radioButtonPurpleTheme -> "purple"
                R.id.radioButtonBlueTheme -> "blue"
                else -> "green"
            }
            settingsViewModel.setColorTheme(colorTheme)
            welcomeViewModel.setSelectedColorTheme(colorTheme)
        }
    }

    private fun restoreThemeState() {
        val prefs =
            requireActivity().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val theme = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val colorTheme = prefs.getString("color_theme", "green")

        when (theme) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.radioButtonLightTheme.isChecked = true
            AppCompatDelegate.MODE_NIGHT_YES -> binding.radioButtonDarkTheme.isChecked = true
            else -> binding.radioButtonSystemTheme.isChecked = true
        }

        when (colorTheme) {
            "purple" -> binding.radioButtonPurpleTheme.isChecked = true
            "blue" -> binding.radioButtonBlueTheme.isChecked = true
            else -> binding.radioButtonGreenTheme.isChecked = true
        }
    }

    private fun setupPermissions() {
        binding.switchLocation.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != hasLocationPermission()) {
                if (isChecked) requestLocationPermission() else binding.switchLocation.isChecked =
                    hasLocationPermission()
            }
        }

        binding.switchStorage.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != hasStoragePermission()) {
                if (isChecked) requestStoragePermission() else binding.switchStorage.isChecked =
                    hasStoragePermission()
            }
        }

        binding.switchNotification.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked != hasNotificationPermission()) {
                if (isChecked) requestNotificationPermission() else binding.switchNotification.isChecked =
                    hasNotificationPermission()
            }
        }

        updateLocationSwitch(hasLocationPermission())
        updateStorageSwitch(hasStoragePermission())
        updateNotificationSwitch(hasNotificationPermission())

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.cardLocationPermission.visibility = View.GONE
            binding.cardStoragePermission.visibility = View.GONE
            welcomeViewModel.setLocationPermissionGranted(true)
            welcomeViewModel.setStoragePermissionGranted(true)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            binding.cardNotificationPermission.visibility = View.GONE
            welcomeViewModel.setNotificationPermissionGranted(true)
        }
    }

    override fun onResume() {
        super.onResume()
        updateLocationSwitch(hasLocationPermission())
        updateStorageSwitch(hasStoragePermission())
        updateNotificationSwitch(hasNotificationPermission())
        welcomeViewModel.setLocationPermissionGranted(hasLocationPermission())
        welcomeViewModel.setStoragePermissionGranted(hasStoragePermission())
        welcomeViewModel.setNotificationPermissionGranted(hasNotificationPermission())
        (activity as? WelcomeActivity)?.updateNavigationButtons(showPrev = true, showNext = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun hasLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val fine = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
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

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (perms.any {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    requireActivity(),
                    it
                )
            }) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.location_permission_title)
                .setMessage(R.string.location_permission_rationale)
                .setPositiveButton(R.string.ok) { _, _ ->
                    requestLocationPermissionLauncher.launch(
                        perms
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            requestLocationPermissionLauncher.launch(perms)
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${requireContext().packageName}".toUri()
                }
                requestStorageAccessLauncher.launch(intent)
            } catch (_: Exception) {
                requestStorageAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    requireActivity(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            ) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.storage_permission_title)
                    .setMessage(R.string.storage_permission_rationale)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        requestLegacyStorageLauncher.launch(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                requestLegacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                requireActivity(),
                Manifest.permission.POST_NOTIFICATIONS
            )
        ) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.notification_permission)
                .setMessage(R.string.notification_permission_rationale)
                .setPositiveButton(R.string.ok) { _, _ ->
                    requestNotificationLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestNextPermission() {
        when {
            binding.cardLocationPermission.visibility == View.VISIBLE && !hasLocationPermission() -> {
                val perms = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                isSequentialRequest = true
                requestLocationPermissionLauncher.launch(perms)
            }

            binding.cardNotificationPermission.visibility == View.VISIBLE && !hasNotificationPermission() -> {
                isSequentialRequest = true
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            binding.cardStoragePermission.visibility == View.VISIBLE && !hasStoragePermission() -> {
                isSequentialRequest = true
                requestStoragePermissionSequential()
            }

            else -> {
                isSequentialRequest = false
                (activity as? WelcomeActivity)?.navigateToNextFragment()
            }
        }
    }

    private fun requestStoragePermissionSequential() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${requireContext().packageName}".toUri()
                }
                requestStorageAccessLauncher.launch(intent)
            } catch (_: Exception) {
                requestStorageAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestLegacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun showLocationDeniedDialog() {
        if (isSequentialRequest) {
            showSequentialDeniedDialog(
                message = getString(R.string.location_permission_denied_message),
                onRetry = {
                    val perms = arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    isSequentialRequest = true
                    requestLocationPermissionLauncher.launch(perms)
                },
                onSkip = { requestNextPermission() }
            )
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.permission_denied)
                .setMessage(R.string.location_permission_denied_message)
                .setPositiveButton(R.string.request_again) { _, _ ->
                    val perms = arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    requestLocationPermissionLauncher.launch(perms)
                }
                .setNegativeButton(R.string.continue_anyway, null)
                .show()
        }
    }

    private fun showStorageDeniedDialog() {
        if (isSequentialRequest) {
            val isSystemBlocked = Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        requireActivity(),
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
            if (isSystemBlocked) {
                showSequentialDeniedDialog(
                    message = getString(R.string.storage_permission_denied_message) + "\n\n" + getString(
                        R.string.permission_manually_required
                    ),
                    onRetry = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:${requireContext().packageName}".toUri()
                        }
                        startActivity(intent)
                    },
                    onSkip = { requestNextPermission() },
                    retryText = getString(R.string.open_settings)
                )
            } else {
                showSequentialDeniedDialog(
                    message = getString(R.string.storage_permission_denied_message),
                    onRetry = {
                        isSequentialRequest = true
                        requestStoragePermissionSequential()
                    },
                    onSkip = { requestNextPermission() }
                )
            }
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.permission_denied)
                .setMessage(R.string.storage_permission_denied_message)
                .setPositiveButton(R.string.request_again) { _, _ ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent =
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = "package:${requireContext().packageName}".toUri()
                                }
                            requestStorageAccessLauncher.launch(intent)
                        } catch (_: Exception) {
                            requestStorageAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    } else {
                        requestLegacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
                .setNegativeButton(R.string.continue_anyway, null)
                .show()
        }
    }

    private fun showNotificationDeniedDialog() {
        if (isSequentialRequest) {
            showSequentialDeniedDialog(
                message = getString(R.string.notification_permission_denied_message),
                onRetry = {
                    isSequentialRequest = true
                    requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onSkip = { requestNextPermission() }
            )
        } else {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.permission_denied)
                .setMessage(R.string.notification_permission_denied_message)
                .setPositiveButton(R.string.request_again) { _, _ ->
                    requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton(R.string.continue_anyway, null)
                .show()
        }
    }

    private fun showSequentialDeniedDialog(
        message: String,
        onRetry: () -> Unit,
        onSkip: () -> Unit,
        retryText: String = getString(R.string.request_again)
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.permission_denied)
            .setMessage(message)
            .setPositiveButton(retryText) { _, _ -> onRetry() }
            .setNegativeButton(R.string.skip) { _, _ -> onSkip() }
            .show()
    }

    private fun showPermissionsChoiceDialog() {
        val context = requireContext()
        val dialog = BottomSheetDialog(context)
        val primaryColor = resolveColor(android.R.attr.colorPrimary)
        val onSurfaceColor = resolveColor(com.google.android.material.R.attr.colorOnSurface)

        val rootLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.text_secondary))
            rootLayout.addView(this)
        }

        val cardView = CardView(context).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(8).toFloat()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(16), 0, dp(16), dp(16))
            layoutParams = lp
        }

        val container = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }

        val iconDrawable = AppCompatResources.getDrawable(context, R.drawable.ic_warning)?.mutate()
        iconDrawable?.let { DrawableCompat.setTint(it, primaryColor) }
        val iconView = androidx.appcompat.widget.AppCompatImageView(context).apply {
            setImageDrawable(iconDrawable)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            }
        }
        container.addView(iconView)

        container.addView(TextView(context).apply {
            text = getString(R.string.permissions_not_granted_title)
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(onSurfaceColor)
            setPadding(0, dp(4), 0, dp(4))
        })

        container.addView(TextView(context).apply {
            text = getString(R.string.permissions_not_granted_message)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(24))
        })

        val btnGrant = MaterialButton(context).apply {
            text = getString(R.string.grant_permission)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener {
                dialog.dismiss()
                isSequentialRequest = true
                requestNextPermission()
            }
        }
        container.addView(btnGrant)

        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
        })

        val btnContinue = TextView(context).apply {
            text = getString(R.string.continue_without_permission)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            minimumHeight = dp(40)
            setPadding(0, dp(8), 0, dp(8))
            val typedValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                typedValue,
                true
            )
            setBackgroundResource(typedValue.resourceId)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                isSequentialRequest = false
                (activity as? WelcomeActivity)?.navigateToNextFragment()
            }
        }
        container.addView(btnContinue)

        cardView.addView(container)
        rootLayout.addView(cardView)
        dialog.setContentView(rootLayout)
        dialog.show()
    }

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(
            requireContext(),
            tv.resourceId
        ) else tv.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateLocationSwitch(granted: Boolean) {
        binding.switchLocation.isChecked = granted
        binding.switchLocation.isEnabled = !granted
    }

    private fun updateStorageSwitch(granted: Boolean) {
        binding.switchStorage.isChecked = granted
        binding.switchStorage.isEnabled = !granted
    }

    private fun updateNotificationSwitch(granted: Boolean) {
        binding.switchNotification.isChecked = granted
        binding.switchNotification.isEnabled = !granted
    }

    fun goNext() {
        val someDenied =
            (binding.cardLocationPermission.visibility == View.VISIBLE && !hasLocationPermission()) ||
                    (binding.cardStoragePermission.visibility == View.VISIBLE && !hasStoragePermission()) ||
                    (binding.cardNotificationPermission.visibility == View.VISIBLE && !hasNotificationPermission())

        if (someDenied) {
            showPermissionsChoiceDialog()
        } else {
            (activity as? WelcomeActivity)?.navigateToNextFragment()
        }
    }

    companion object {
        fun newInstance() = WelcomeThemePermissionsFragment()
    }
}
