package com.lsd.wififrankenstein

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.databinding.ActivityMainBinding
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel
import com.lsd.wififrankenstein.ui.updates.UpdateChecker
import kotlinx.coroutines.launch
import org.json.JSONObject


class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val viewModel by viewModels<MainViewModel>()
    private lateinit var updateChecker: UpdateChecker
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)

        updateChecker = UpdateChecker(applicationContext)

        val shouldCheckUpdates = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("check_updates_on_open", true)

        if (shouldCheckUpdates) {
            checkForUpdates()
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_wifi_scanner,
                R.id.nav_wifi_analysis,
                R.id.nav_database_finder,
                R.id.nav_wifi_map,
                R.id.nav_wps_generator,
                R.id.nav_wpa_generator,
                R.id.nav_settings,
                R.id.nav_about
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        settingsViewModel.showWipFeatures.observe(this) { showWipFeatures ->
            binding.navView.menu.findItem(R.id.nav_rss_news)?.isVisible = showWipFeatures
        }

        binding.appBarMain.fab1.setOnClickListener { view ->
            Snackbar.make(view, getString(R.string.secondary_action_1), Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
        }

        binding.appBarMain.fab2.setOnClickListener { view ->
            Snackbar.make(view, getString(R.string.secondary_action_2), Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
        }

        lifecycleScope.launch {
            viewModel.checkAndCopyFiles(applicationContext)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            updateChecker.checkForUpdates()
                .collect { status ->
                    val updateJson = JSONObject().apply {
                        put("hasAppUpdate", status.appUpdate != null)
                        put("hasSystemUpdates", status.fileUpdates.any { it.needsUpdate })
                        put("hasDbUpdates", status.dbUpdates.any { it.needsUpdate })
                        put("hasAnyUpdates", status.hasUpdates)
                        status.appUpdate?.let {
                            put("newVersion", it.newVersion)
                        }
                    }
                    getSharedPreferences("updates", MODE_PRIVATE)
                        .edit {
                            putString("update_status", updateJson.toString())
                        }
                }
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
}