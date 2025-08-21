package com.lsd.wififrankenstein

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.databinding.ActivityMainBinding
import com.lsd.wififrankenstein.network.NetworkUtils
import com.lsd.wififrankenstein.ui.NavHeaderHelper
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel
import com.lsd.wififrankenstein.ui.updates.UpdateChecker
import com.lsd.wififrankenstein.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.osmdroid.library.BuildConfig

class ShellInitializer : Shell.Initializer() {
    override fun onInit(context: Context, shell: Shell): Boolean {
        return try {
            shell.newJob()
                .add("export PATH=\$PATH:/system/bin:/system/xbin")
                .add("umask 022")
                .exec()
            true
        } catch (e: Exception) {
            false
        }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val viewModel by viewModels<MainViewModel>()
    private lateinit var updateChecker: UpdateChecker
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val categoryStates = mutableMapOf(
        "root_functions" to false,
        "generators" to false,
        "utilities" to false,
        "api_3wifi" to false
    )

    companion object {
        init {
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setInitializers(ShellInitializer::class.java)
                .setTimeout(15))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            applyTheme()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error applying theme", e)
        }

        super.onCreate(savedInstanceState)

        updateChecker = UpdateChecker(applicationContext)
        handleNotificationIntent(intent)

        val shouldCheckUpdates = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("check_updates_on_open", true)

        if (shouldCheckUpdates) {
            lifecycleScope.launch(Dispatchers.IO) {
                checkForUpdates()
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView = binding.navView
        val headerView = navView.getHeaderView(0)
        NavHeaderHelper.setupNavHeader(this, headerView)

        val navController = findNavController(R.id.nav_host_fragment_content_main)

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_wifi_scanner,
                R.id.nav_wifi_analysis,
                R.id.nav_database_finder,
                R.id.nav_wifi_map,
                R.id.nav_qr_generator,
                R.id.nav_saved_passwords,
                R.id.nav_wps_generator,
                R.id.nav_wpa_generator,
                R.id.nav_pixiedust,
                R.id.nav_settings,
                R.id.nav_about
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)

        setupCategoryClickListeners(navController)

        settingsViewModel.showWipFeatures.observe(this) { showWipFeatures ->
            binding.navView.menu.findItem(R.id.nav_rss_news)?.isVisible = showWipFeatures
        }

        settingsViewModel.enableRoot.observe(this) { enableRoot ->
            val categoryRootItem = binding.navView.menu.findItem(R.id.category_root_functions)
            val pixiedustItem = binding.navView.menu.findItem(R.id.nav_pixiedust)
            val savedPasswordsItem = binding.navView.menu.findItem(R.id.nav_saved_passwords)

            categoryRootItem?.isVisible = enableRoot

            if (enableRoot && categoryStates["root_functions"] == true) {
                pixiedustItem?.isVisible = true
                savedPasswordsItem?.isVisible = true
            } else {
                pixiedustItem?.isVisible = false
                savedPasswordsItem?.isVisible = false
            }
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

    private fun handleNotificationIntent(intent: Intent?) {
        intent?.let {
            when {
                it.getBooleanExtra("open_updates", false) -> {
                    findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_updates)
                }
                it.getBooleanExtra("open_db_setup", false) -> {
                    findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.dbSetupFragment)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun setupCategoryClickListeners(navController: NavController) {
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            Log.d("MainActivity", "Menu item clicked: ${menuItem.itemId}")
            when (menuItem.itemId) {
                R.id.category_root_functions -> {
                    Log.d("MainActivity", "Root functions category clicked")
                    toggleCategory("root_functions", listOf(
                        R.id.nav_pixiedust,
                        R.id.nav_iw_scanner,
                        R.id.nav_saved_passwords
                    ), menuItem)
                    true
                }
                R.id.category_generators -> {
                    Log.d("MainActivity", "Generators category clicked")
                    toggleCategory("generators", listOf(
                        R.id.nav_wps_generator,
                        R.id.nav_wpa_generator
                    ), menuItem)
                    true
                }
                R.id.category_api_3wifi -> {
                    toggleCategory("api_3wifi", listOf(
                        R.id.nav_api_query,
                        R.id.nav_upload_routerscan
                    ), menuItem)
                    true
                }
                R.id.category_utilities -> {
                    toggleCategory("utilities", listOf(
                        R.id.nav_mac_location,
                        R.id.nav_wifi_analysis,
                        R.id.nav_qr_generator,
                        R.id.nav_ip_ranges,
                        R.id.nav_convert_dumps
                    ), menuItem)
                    true
                }
                else -> {
                    try {
                        val handled = NavigationUI.onNavDestinationSelected(menuItem, navController)
                        if (handled) {
                            binding.drawerLayout.closeDrawers()
                        }
                        handled
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Navigation failed", e)
                        false
                    }
                }
            }
        }
    }

    private fun toggleCategory(categoryKey: String, itemIds: List<Int>, categoryItem: MenuItem) {
        val isExpanded = categoryStates[categoryKey] ?: false
        categoryStates[categoryKey] = !isExpanded

        val menu = binding.navView.menu

        itemIds.forEach { itemId ->
            val item = menu.findItem(itemId)
            if (categoryKey == "root_functions") {
                val enableRoot = settingsViewModel.enableRoot.value ?: false
                item?.isVisible = !isExpanded && enableRoot
            } else {
                item?.isVisible = !isExpanded
            }
        }

        val iconRes = if (!isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        categoryItem.setIcon(iconRes)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun checkForUpdates() {
        if (!NetworkUtils.hasActiveConnection(this)) {
            Log.w("MainActivity", "No internet connection available")
            return
        }

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

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart called")
    }

    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "onResume called")
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause called")
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop called")
    }

    override fun onDestroy() {
        Log.d("MainActivity", "onDestroy called")
        super.onDestroy()
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