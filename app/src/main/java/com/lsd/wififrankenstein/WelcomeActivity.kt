package com.lsd.wififrankenstein

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.lsd.wififrankenstein.databinding.ActivityWelcomeBinding
import com.lsd.wififrankenstein.ui.welcome.WelcomeCompletedFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomeDatabasesFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomeDisclaimerFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomePermissionsFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomeThemeFragment
import com.lsd.wififrankenstein.ui.welcome.WelcomeUpdatesFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private val viewModel by viewModels<WelcomeViewModel>()

    private lateinit var viewPager: ViewPager2
    private val fragments = listOf(
        WelcomeDisclaimerFragment.newInstance(),
        WelcomeThemeFragment.newInstance(),
        WelcomePermissionsFragment.newInstance(),
        WelcomeDatabasesFragment.newInstance(),
        WelcomeUpdatesFragment.newInstance(),
        WelcomeCompletedFragment.newInstance()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

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
        }
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val colorTheme = prefs.getString("color_theme", "purple")
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
            else -> if (isDarkTheme) R.style.Theme_WIFIFrankenstein_Purple_Night else R.style.Theme_WIFIFrankenstein_Purple
        }
        setTheme(themeResId)
    }

    private fun setupViewPager() {
        viewPager = binding.welcomeViewPager
        viewPager.adapter = OnboardingAdapter(this)
        viewPager.isUserInputEnabled = false

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtonVisibility(position)
            }
        })
    }

    private fun setupButtons() {
        binding.buttonNext.setOnClickListener {
            if (viewPager.currentItem < fragments.size - 1) {
                val currentFragment = fragments[viewPager.currentItem]
                if (currentFragment is WelcomeDatabasesFragment) {
                    currentFragment.goNext()
                } else {
                    viewPager.currentItem++
                }
            }
        }

        binding.buttonPrev.setOnClickListener {
            if (viewPager.currentItem > 0) {
                val currentFragment = fragments[viewPager.currentItem]
                if (currentFragment is WelcomeDatabasesFragment) {
                    currentFragment.goBack()
                } else {
                    viewPager.currentItem--
                }
            }
        }

        updateButtonVisibility(0)
    }

    fun navigateToNextFragment() {
        if (viewPager.currentItem < fragments.size - 1) {
            viewPager.currentItem++
            updateButtonVisibility(viewPager.currentItem)
        }
    }

    private fun updateButtonVisibility(position: Int) {
        binding.buttonPrev.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
        binding.buttonNext.visibility = if (position < fragments.size - 1) View.VISIBLE else View.GONE
    }

    fun completeOnboarding() {
        startMainActivity()
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    fun updateNavigationButtons(showPrev: Boolean, showSkip: Boolean, showNext: Boolean,
                                skipText: String = getString(R.string.skip),
                                nextText: String = getString(R.string.next)) {
        binding.buttonPrev.visibility = if (showPrev) View.VISIBLE else View.INVISIBLE
        binding.buttonNext.visibility = if (showNext) View.VISIBLE else View.GONE
        binding.buttonNext.text = nextText
    }

    inner class OnboardingAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = fragments.size

        override fun createFragment(position: Int): Fragment {
            return fragments[position]
        }
    }
}