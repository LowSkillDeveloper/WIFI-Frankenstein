package com.lsd.wififrankenstein

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.databinding.ActivityWelcomeBinding
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel
import com.lsd.wififrankenstein.ui.welcome.ChrootInstallFragment

import com.lsd.wififrankenstein.ui.welcome.WelcomeCompletedFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomeDatabasesFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomeDisclaimerFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomeRootFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomeThemePermissionsFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomeUpdatesFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomeVersionCheckFragment
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private val viewModel by viewModels<WelcomeViewModel>()
    private var exitReady = false
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (viewPager.currentItem > 0) {
                viewPager.currentItem--
                updateButtonVisibility(viewPager.currentItem)
            } else {
                if (exitReady) {
                    finish()
                } else {
                    exitReady = true
                    Snackbar.make(binding.root, R.string.exit_app_message, Snackbar.LENGTH_SHORT)
                        .show()
                    lifecycleScope.launch {
                        delay(2000)
                        exitReady = false
                    }
                }
            }
        }
    }

    private lateinit var viewPager: ViewPager2
    private val fragments by lazy {
        listOf(
            WelcomeVersionCheckFragment.newInstance(),
            WelcomeDisclaimerFragment.newInstance(),
            WelcomeThemePermissionsFragment.newInstance(),
            WelcomeDatabasesFragment.newInstance(),
            WelcomeRootFragment.newInstance(),
            ChrootInstallFragment.newInstance(),
            WelcomeUpdatesFragment.newInstance(),
            WelcomeCompletedFragment.newInstance()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        com.lsd.wififrankenstein.util.Log.d("WelcomeActivity", "Welcome activity started")
        onBackPressedDispatcher.addCallback(this, backCallback)
        lifecycleScope.launch {
            val isFirstLaunch = withContext(Dispatchers.IO) {
                viewModel.isFirstLaunch()
            }
            if (!isFirstLaunch) {
                startMainActivity()
                return@launch
            }

            binding = ActivityWelcomeBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupViewPager()
            setupButtons()

            viewModel.selectedDatabases.observe(this@WelcomeActivity) { dbs ->
                updateDatabasesButton(dbs.isNotEmpty())
            }
        }
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val colorTheme = prefs.getString("color_theme", "green")
        val nightMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        AppCompatDelegate.setDefaultNightMode(nightMode)

        val isDarkTheme = when (nightMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }

        val themeResId = when (colorTheme) {
            "purple" -> if (isDarkTheme) R.style.Theme_WIFIFrankenstein_Purple_Night else R.style.Theme_WIFIFrankenstein_Purple
            "green" -> if (isDarkTheme) R.style.Theme_WIFIFrankenstein_Green_Night else R.style.Theme_WIFIFrankenstein_Green
            "blue" -> if (isDarkTheme) R.style.Theme_WIFIFrankenstein_Blue_Night else R.style.Theme_WIFIFrankenstein_Blue
            else -> if (isDarkTheme) R.style.Theme_WIFIFrankenstein_Green_Night else R.style.Theme_WIFIFrankenstein_Green
        }
        setTheme(themeResId)
    }

    private fun setupViewPager() {
        viewPager = binding.welcomeViewPager
        viewPager.adapter = OnboardingAdapter(this)
        viewPager.isUserInputEnabled = false

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.textViewBottomHint.visibility = View.GONE
                updateButtonVisibility(position)
                handlePageChange(position)
            }
        })
    }

    private fun handlePageChange(position: Int) {
        val currentFragment = fragments[position]
        when (currentFragment) {
            is WelcomeVersionCheckFragment -> currentFragment.checkVersion()
        }
    }

    private fun setupButtons() {
        binding.buttonNext.setOnClickListener {
            if (viewPager.currentItem < fragments.size - 1) {
                val currentFragment = fragments[viewPager.currentItem]
                when (currentFragment) {
                    is WelcomeRootFragment -> currentFragment.goNext()
                    is WelcomeThemePermissionsFragment -> currentFragment.goNext()
                    else -> viewPager.currentItem++
                }
            }
        }

        binding.buttonSkip.setOnClickListener {
            handleSkip()
        }

        binding.buttonPrev.setOnClickListener {
            if (viewPager.currentItem > 0) {
                viewPager.currentItem--
            }
        }

        updateButtonVisibility(0)
    }

    fun navigateToNextFragment() {
        if (viewPager.currentItem < fragments.size - 1) {
            viewPager.currentItem++
        }
    }

    private fun updateButtonVisibility(position: Int) {
        if (position == 0 || position == 1) {
            binding.bottomBar.visibility = View.GONE
        } else {
            binding.bottomBar.visibility = View.VISIBLE
            binding.buttonPrev.visibility = if (position > 0) View.VISIBLE else View.GONE
            if (position == 3) {
                updateDatabasesButton(viewModel.selectedDatabases.value?.isNotEmpty() == true)
            } else {
                binding.buttonNext.visibility =
                    if (position < fragments.size - 1) View.VISIBLE else View.GONE
                binding.buttonSkip.visibility = View.GONE
            }
        }
    }

    private fun updateDatabasesButton(hasDatabases: Boolean) {
        if (viewPager.currentItem == 3) {
            if (hasDatabases) {
                binding.buttonSkip.visibility = View.GONE
                binding.buttonNext.visibility = View.VISIBLE
                binding.buttonNext.text = getString(R.string.next)
            } else {
                binding.buttonSkip.visibility = View.VISIBLE
                binding.buttonSkip.text = getString(R.string.skip)
                binding.buttonNext.visibility = View.GONE
            }
        }
    }

    fun navigateToChrootInstall() {
        val currentIndex = viewPager.currentItem
        if (currentIndex < fragments.size - 1) {
            viewPager.currentItem = currentIndex + 1
        }
    }

    fun goToRoot() {
        viewPager.currentItem = 4
    }

    fun setBottomHint(text: String?) {
        if (!text.isNullOrEmpty()) {
            binding.textViewBottomHint.text = text
            binding.textViewBottomHint.visibility = View.VISIBLE
        } else {
            binding.textViewBottomHint.visibility = View.GONE
        }
    }

    fun completeOnboarding() {
        startMainActivity()
    }

    private fun startMainActivity() {
        try {
            applySelectedIcon()

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            com.lsd.wififrankenstein.util.Log.e("WelcomeActivity", "Error starting MainActivity", e)

            val packageManager = packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
            finish()
        }
    }

    private fun applySelectedIcon() {
        val prefs = getSharedPreferences("com.lsd.wififrankenstein", MODE_PRIVATE)
        val selectedIcon = prefs.getString("app_icon", "default") ?: "default"

        if (selectedIcon != "default") {
            val settingsViewModel = SettingsViewModel(application)
            settingsViewModel.setAppIcon(selectedIcon)
        }
    }

    fun updateNavigationButtons(
        showPrev: Boolean,
        showNext: Boolean,
        nextText: String = getString(R.string.next)
    ) {
        binding.buttonPrev.visibility = if (showPrev) View.VISIBLE else View.GONE
        binding.buttonNext.visibility = if (showNext) View.VISIBLE else View.GONE
        binding.buttonNext.text = nextText
    }

    fun setBottomBarVisible(visible: Boolean) {
        binding.bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun handleSkip() {
        viewPager.currentItem++
    }

    override fun onStart() {
        super.onStart()
        Log.d("WelcomeActivity", "onStart called")
    }

    override fun onResume() {
        super.onResume()
        Log.d("WelcomeActivity", "onResume called")
    }

    override fun onPause() {
        super.onPause()
        Log.d("WelcomeActivity", "onPause called")
    }

    override fun onStop() {
        super.onStop()
        Log.d("WelcomeActivity", "onStop called")
    }

    override fun onDestroy() {
        Log.d("WelcomeActivity", "onDestroy called")
        super.onDestroy()
    }

    inner class OnboardingAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = fragments.size

        override fun createFragment(position: Int): Fragment {
            return fragments[position]
        }
    }
}
