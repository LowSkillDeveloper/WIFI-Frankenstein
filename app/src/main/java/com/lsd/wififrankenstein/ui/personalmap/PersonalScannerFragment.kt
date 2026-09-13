package com.lsd.wififrankenstein.ui.personalmap

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.DialogPersonalMapSetupBinding
import com.lsd.wififrankenstein.databinding.FragmentPersonalWifiMapBinding
import java.text.NumberFormat

class PersonalScannerFragment : Fragment() {

    private var _binding: FragmentPersonalWifiMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PersonalWiFiMapViewModel by activityViewModels()
    private val recentAdapter = RecentNetworksAdapter()

    private val numberFormat = NumberFormat.getIntegerInstance()

    private var pulseAnimator: ObjectAnimator? = null

    private var scanPrefs: SharedPreferences? = null
    private val scanPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PersonalMapScanSettings.PREF_ADAPTIVE ||
                key == PersonalMapScanSettings.PREF_INTERVAL_MANUAL
            ) {
                if (_binding != null) updateScanRateLabel()
            }
            if (key == PersonalMapScanSettings.PREF_KEEP_SCREEN_ON) {
                applyKeepScreenOn()
            }
        }

    private var lastScanBase = ""
    private var sessionTimerRunning = false
    private val sessionTimerRunnable = object : Runnable {
        override fun run() {
            if (!sessionTimerRunning) return
            val root = _binding?.root ?: return
            updateSessionTimer()
            root.postDelayed(this, TIMER_TICK_MS)
        }
    }

    private var autoScrollEnabled = true

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val root = _binding?.root ?: return@registerForActivityResult
        if (result.values.any { it }) {
            beginScanning()
            return@registerForActivityResult
        }
        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
            requireActivity(), Manifest.permission.ACCESS_FINE_LOCATION
        )
        val snackbar = Snackbar.make(
            root, R.string.location_permission_required, Snackbar.LENGTH_LONG
        )
        if (canAskAgain) {
            snackbar.setAction(R.string.grant_permission) { ensureLocationAndStart() }
        } else {
            snackbar.setAction(R.string.open_settings) { openAppSettings() }
        }
        snackbar.show()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalWifiMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerRecentNetworks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recentAdapter
            itemAnimator?.addDuration = 150
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }

        binding.recyclerRecentNetworks.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy == 0) return
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = lm.findLastCompletelyVisibleItemPosition()
                    val total = recentAdapter.itemCount
                    val atBottom = total == 0 || lastVisible >= total - 1
                    if (atBottom) {
                        if (!autoScrollEnabled) {
                            autoScrollEnabled = true
                            binding.fabScrollBottom.hide()
                        }
                    } else if (dy > 0) {
                        if (autoScrollEnabled) {
                            autoScrollEnabled = false
                            binding.fabScrollBottom.show()
                        }
                    }
                }
            }
        )

        binding.fabScrollBottom.setOnClickListener {
            autoScrollEnabled = true
            binding.fabScrollBottom.hide()
            scrollToBottom()
        }

        setupInitialState()
        setupObservers()
        setupListeners()
    }

    private fun scrollToBottom() {
        val count = recentAdapter.itemCount
        if (count > 0) {
            binding.recyclerRecentNetworks.smoothScrollToPosition(count - 1)
        }
    }

    private fun setupInitialState() {
        scanPrefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        scanPrefs?.registerOnSharedPreferenceChangeListener(scanPrefsListener)
        updateScanRateLabel()
    }

    private fun updateScanRateLabel() {
        val prefs = scanPrefs ?: return
        val rateText = if (PersonalMapScanSettings.isAdaptive(prefs)) {
            getString(R.string.pm_scan_adaptive)
        } else {
            when (prefs.getLong(
                PersonalMapScanSettings.PREF_INTERVAL_MANUAL,
                PersonalMapScanSettings.DEFAULT_WALK_MS
            )) {
                PersonalMapScanSettings.DEFAULT_STILL_MS -> getString(R.string.pm_scan_tier_still)
                PersonalMapScanSettings.DEFAULT_DRIVE_MS -> getString(R.string.pm_scan_tier_drive)
                PersonalMapScanSettings.DEFAULT_VERY_FAST_MS -> getString(R.string.pm_scan_tier_very_fast)
                else -> getString(R.string.pm_scan_tier_walk)
            }
        }
        binding.textScanRate.text = getString(R.string.pm_scan_rate_label, rateText)
    }

    private fun setupObservers() {
        viewModel.isScanning.observe(viewLifecycleOwner) { isScanning ->
            if (!isAdded) return@observe
            applyKeepScreenOn()
            if (isScanning) {
                binding.btnToggleScan.text = getString(R.string.pm_btn_stop)
                binding.btnToggleScan.setIconResource(R.drawable.ic_close)
                binding.btnToggleScan.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.signal_poor)
                startPulseAnimation()
                binding.textStatus.text = getString(R.string.scanning_active)
                binding.textStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.signal_good)
                )
                startSessionTimer()
            } else {
                binding.btnToggleScan.text = getString(R.string.pm_btn_start)
                binding.btnToggleScan.setIconResource(R.drawable.ic_scan)
                binding.btnToggleScan.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.signal_good)
                stopPulseAnimation()
                binding.textStatus.text = getString(R.string.scanning_idle)
                binding.textStatus.setTextColor(
                    binding.textStatus.textColors
                )
                stopSessionTimer()
                updateSessionTimer()
            }
        }

        viewModel.gpsHealth.observe(viewLifecycleOwner) { health ->
            if (!isAdded) return@observe
            val healthText = when (health) {
                "EXCELLENT" -> getString(R.string.gps_health_excellent)
                "GOOD" -> getString(R.string.gps_health_good)
                "UNRELIABLE" -> getString(R.string.gps_health_unreliable)
                "WIFI_RECOVERED" -> getString(R.string.gps_health_recovered)
                else -> getString(R.string.gps_health_bad)
            }
            binding.textGpsHealth.text = getString(R.string.gps_health, healthText)
            val colorRes = when (health) {
                "EXCELLENT", "GOOD" -> R.color.signal_good
                "WIFI_RECOVERED", "UNRELIABLE" -> R.color.signal_fair
                else -> R.color.signal_poor
            }
            binding.textGpsHealth.setTextColor(
                ContextCompat.getColor(requireContext(), colorRes)
            )
        }

        viewModel.stats.observe(viewLifecycleOwner) { stats ->
            if (!isAdded) return@observe
            binding.statTotal.text = numberFormat.format(stats.total)
            binding.statReliable.text = numberFormat.format(stats.reliable)
            binding.statLastAdded.text = PersonalMapUtils.formatTimeShort(stats.lastSeen, resources)
        }

        viewModel.sessionStats.observe(viewLifecycleOwner) { session ->
            if (!isAdded) return@observe
            binding.statDistinct.text = if (session.distinctCapped) {
                getString(R.string.stat_capped_suffix, numberFormat.format(session.distinctNetworks))
            } else {
                numberFormat.format(session.distinctNetworks)
            }
            binding.statMeasurements.text = numberFormat.format(session.measurements)
            binding.statScans.text = numberFormat.format(session.scans)
            lastScanBase = getString(
                R.string.last_scan_summary,
                session.lastFound,
                session.lastNew,
                session.lastUpdated
            )
            binding.textLastScan.text = lastScanBase
        }

        viewModel.gpsDetail.observe(viewLifecycleOwner) { gps ->
            if (!isAdded) return@observe
            binding.textGpsDetails.text = getString(
                R.string.gps_details,
                gps.accuracy,
                gps.satellites,
                gps.fixAgeMs / 1000L,
                gps.speed
            )
        }

        viewModel.recentNetworks.observe(viewLifecycleOwner) { entries ->
            if (!isAdded) return@observe
            recentAdapter.submitList(entries)
            binding.textLogEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            if (autoScrollEnabled && entries.isNotEmpty()) {
                binding.recyclerRecentNetworks.post { scrollToBottom() }
            }
        }
    }

    private fun setupListeners() {
        binding.btnToggleScan.setOnClickListener {
            if (viewModel.isScanning.value == true) {
                viewModel.stopScanning()
            } else {
                ensureLocationAndStart()
            }
        }

        binding.btnSettings.setOnClickListener {
            showSetupDialog()
        }
    }

    private fun showSetupDialog() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val dialogBinding = DialogPersonalMapSetupBinding.inflate(layoutInflater)

        dialogBinding.cbCrossCheck.isChecked = prefs.getBoolean(PersonalWiFiMapFragment.KEY_CROSS_CHECK, true)
        dialogBinding.cbWpasec.isChecked = prefs.getBoolean(PersonalWiFiMapFragment.KEY_WPASEC, true)
        dialogBinding.cbKeepScreenOn.isChecked = PersonalMapScanSettings.isKeepScreenOn(prefs)

        val adaptive = PersonalMapScanSettings.isAdaptive(prefs)
        val manual = prefs.getLong(
            PersonalMapScanSettings.PREF_INTERVAL_MANUAL,
            PersonalMapScanSettings.DEFAULT_WALK_MS
        )
        when {
            adaptive -> dialogBinding.rbAdaptive.isChecked = true
            manual == PersonalMapScanSettings.DEFAULT_STILL_MS -> dialogBinding.rbStill.isChecked = true
            manual == PersonalMapScanSettings.DEFAULT_DRIVE_MS -> dialogBinding.rbDrive.isChecked = true
            manual == PersonalMapScanSettings.DEFAULT_VERY_FAST_MS -> dialogBinding.rbVeryFast.isChecked = true
            else -> dialogBinding.rbWalk.isChecked = true
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.pm_setup_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.edit {
                    putBoolean(PersonalWiFiMapFragment.KEY_CROSS_CHECK, dialogBinding.cbCrossCheck.isChecked)
                    putBoolean(PersonalWiFiMapFragment.KEY_WPASEC, dialogBinding.cbWpasec.isChecked)
                    putBoolean(PersonalMapScanSettings.PREF_KEEP_SCREEN_ON, dialogBinding.cbKeepScreenOn.isChecked)
                    val isAdaptive = dialogBinding.rbAdaptive.isChecked
                    putBoolean(PersonalMapScanSettings.PREF_ADAPTIVE, isAdaptive)
                    if (!isAdaptive) {
                        val interval = when {
                            dialogBinding.rbStill.isChecked -> PersonalMapScanSettings.DEFAULT_STILL_MS
                            dialogBinding.rbDrive.isChecked -> PersonalMapScanSettings.DEFAULT_DRIVE_MS
                            dialogBinding.rbVeryFast.isChecked -> PersonalMapScanSettings.DEFAULT_VERY_FAST_MS
                            else -> PersonalMapScanSettings.DEFAULT_WALK_MS
                        }
                        putLong(PersonalMapScanSettings.PREF_INTERVAL_MANUAL, interval)
                    }
                    putBoolean(PersonalWiFiMapFragment.KEY_SETUP_DONE, true)
                }
            }
            .setNegativeButton(R.string.skip) { _, _ ->
                prefs.edit { putBoolean(PersonalWiFiMapFragment.KEY_SETUP_DONE, true) }
            }
            .setCancelable(true)
            .show()
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(binding.textStatus, View.ALPHA, 1f, 0.3f).apply {
            duration = PULSE_DURATION_MS
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.textStatus.alpha = 1f
    }

    private fun startSessionTimer() {
        if (sessionTimerRunning) return
        sessionTimerRunning = true
        _binding?.root?.postDelayed(sessionTimerRunnable, TIMER_TICK_MS)
    }

    private fun stopSessionTimer() {
        sessionTimerRunning = false
        _binding?.root?.removeCallbacks(sessionTimerRunnable)
    }

    private fun ensureLocationAndStart() {
        val context = context ?: return
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            beginScanning()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun beginScanning() {
        autoScrollEnabled = true
        binding.fabScrollBottom.hide()
        viewModel.startScanning(saveToDb = true)
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", requireContext().packageName, null)
        )
        runCatching { startActivity(intent) }
    }

    private fun applyKeepScreenOn() {
        val window = activity?.window ?: return
        val prefs = scanPrefs ?: context?.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val keepOn = prefs != null &&
            PersonalMapScanSettings.isKeepScreenOn(prefs) &&
            viewModel.isScanning.value == true
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateSessionTimer() {
        val session = viewModel.sessionStats.value ?: return
        if (session.startedAt <= 0L) return
        val elapsedMs = System.currentTimeMillis() - session.startedAt
        val minutes = (elapsedMs / 60_000).toInt()
        val seconds = ((elapsedMs % 60_000) / 1000).toInt()
        val timerText = getString(R.string.pm_session_elapsed, minutes, seconds)
        binding.textLastScan.text = buildString {
            if (lastScanBase.isNotEmpty()) {
                append(lastScanBase)
                append(" • ")
            }
            append(timerText)
        }
    }

    override fun onResume() {
        super.onResume()
        applyKeepScreenOn()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pulseAnimator?.cancel()
        pulseAnimator = null
        stopSessionTimer()
        runCatching { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        scanPrefs?.unregisterOnSharedPreferenceChangeListener(scanPrefsListener)
        scanPrefs = null
        binding.recyclerRecentNetworks.adapter = null
        _binding = null
    }

    companion object {
        private const val PULSE_DURATION_MS = 800L
        private const val TIMER_TICK_MS = 1000L
    }
}
