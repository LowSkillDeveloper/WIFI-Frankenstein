package com.lsd.wififrankenstein.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.databinding.FragmentWelcomeThemeBinding
import com.lsd.wififrankenstein.ui.settings.AppIconAdapter
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel

class WelcomeThemeFragment : Fragment() {

    private var _binding: FragmentWelcomeThemeBinding? = null
    private val binding get() = _binding!!
    private val settingsViewModel: SettingsViewModel by activityViewModels()
    private val welcomeViewModel: WelcomeViewModel by activityViewModels()
    private lateinit var iconAdapter: AppIconAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeThemeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupThemeButtons()
        setupColorTheme()
        setupIconSettings()
    }

    private fun setupThemeButtons() {
        binding.radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.radioButtonSystemTheme -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                R.id.radioButtonLightTheme -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioButtonDarkTheme -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            settingsViewModel.setTheme(theme)
            welcomeViewModel.setSelectedTheme(theme)
        }
    }

    private fun setupColorTheme() {
        binding.radioGroupColorTheme.setOnCheckedChangeListener { _, checkedId ->
            val colorTheme = when (checkedId) {
                R.id.radioButtonPurpleTheme -> "purple"
                R.id.radioButtonGreenTheme -> "green"
                R.id.radioButtonBlueTheme -> "blue"
                else -> "purple"
            }
            settingsViewModel.setColorTheme(colorTheme)
            welcomeViewModel.setSelectedColorTheme(colorTheme)
        }
    }

    private fun setupIconSettings() {
        val icons = listOf(
            Pair(getString(R.string.icon_default), R.mipmap.ic_launcher),
            Pair(getString(R.string.icon_3wifi), R.mipmap.ic_launcher_3wifi),
            Pair(getString(R.string.icon_anti3wifi), R.mipmap.ic_launcher_anti3wifi),
            Pair(getString(R.string.icon_p3wifi), R.mipmap.ic_launcher_p3wifi),
            Pair(getString(R.string.icon_p3wifi_pixel), R.mipmap.ic_launcher_p3wifi_pixel)
        )

        iconAdapter = AppIconAdapter(requireContext(), icons)
        binding.spinnerAppIcon.adapter = iconAdapter

        binding.spinnerAppIcon.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedIcon = when (position) {
                    0 -> "default"
                    1 -> "3wifi"
                    2 -> "anti3wifi"
                    3 -> "p3wifi"
                    4 -> "p3wifi_pixel"
                    else -> "default"
                }
                settingsViewModel.setAppIcon(selectedIcon)
                welcomeViewModel.setSelectedAppIcon(selectedIcon)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = WelcomeThemeFragment()
    }
}