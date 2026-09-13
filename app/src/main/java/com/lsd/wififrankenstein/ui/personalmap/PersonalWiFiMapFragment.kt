package com.lsd.wififrankenstein.ui.personalmap

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.DialogPersonalMapSetupBinding
import com.lsd.wififrankenstein.databinding.FragmentPersonalMapHostBinding
import com.lsd.wififrankenstein.ui.wifimap.WiFiMapFragment

class PersonalWiFiMapFragment : Fragment() {

    private var _binding: FragmentPersonalMapHostBinding? = null
    private val binding get() = _binding!!
    private var tabMediator: TabLayoutMediator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalMapHostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.personalPager.adapter = PersonalPagerAdapter(this)
        binding.personalPager.isUserInputEnabled = false

        binding.personalPager.offscreenPageLimit = 1
        tabMediator = TabLayoutMediator(binding.personalTabs, binding.personalPager) { tab, position ->
            tab.setText(
                when (position) {
                    0 -> R.string.pm_tab_scanner
                    1 -> R.string.pm_tab_map
                    else -> R.string.pm_tab_database
                }
            )
        }.also { it.attach() }

        maybeShowSetupDialog()
    }

    private fun maybeShowSetupDialog() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SETUP_DONE, false)) return
        showSetupDialog()
    }

    private fun showSetupDialog() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val dialogBinding = DialogPersonalMapSetupBinding.inflate(layoutInflater)

        dialogBinding.cbCrossCheck.isChecked = prefs.getBoolean(KEY_CROSS_CHECK, true)
        dialogBinding.cbWpasec.isChecked = prefs.getBoolean(KEY_WPASEC, true)
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
                    putBoolean(KEY_CROSS_CHECK, dialogBinding.cbCrossCheck.isChecked)
                    putBoolean(KEY_WPASEC, dialogBinding.cbWpasec.isChecked)
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
                    putBoolean(KEY_SETUP_DONE, true)
                }
            }
            .setNegativeButton(R.string.skip) { _, _ ->
                prefs.edit { putBoolean(KEY_SETUP_DONE, true) }
            }
            .setCancelable(true)
            .show()
    }

    fun switchToMapTab(bssid: String? = null) {
        pendingHighlightBssid = bssid
        binding.personalPager.setCurrentItem(1, true)
    }

    fun refreshMapTab() {
        if (_binding == null) return
        if (binding.personalPager.currentItem != 1) return
        val mapFragment = childFragmentManager.findFragmentByTag("f1")
        if (mapFragment is WiFiMapFragment && mapFragment.isAdded) {
            mapFragment.refreshPersonalData()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tabMediator?.detach()
        tabMediator = null
        binding.personalPager.adapter = null
        _binding = null
    }

    private class PersonalPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> PersonalScannerFragment()
            1 -> WiFiMapFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(PersonalWiFiMapViewModel.ARG_SELECT_PERSONAL_MAP, true)
                    putBoolean(PersonalWiFiMapViewModel.ARG_PERSONAL_MODE, true)
                }
            }
            else -> PersonalDatabaseFragment()
        }
    }

    companion object {
        var pendingHighlightBssid: String? = null
        const val KEY_SETUP_DONE = "personal_map_setup_done"
        const val KEY_CROSS_CHECK = "personal_cross_check_enabled"
        const val KEY_WPASEC = "personal_wpasec_enabled"
    }
}
