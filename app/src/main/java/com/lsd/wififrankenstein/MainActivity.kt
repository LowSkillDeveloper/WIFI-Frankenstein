package com.lsd.wififrankenstein

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.databinding.ActivityMainBinding
import com.lsd.wififrankenstein.databinding.DialogUsbWifiDetectedBinding
import com.lsd.wififrankenstein.network.NetworkUtils
import com.lsd.wififrankenstein.ui.drawer.DrawerItem
import com.lsd.wififrankenstein.ui.drawer.DrawerMenuAdapter
import com.lsd.wififrankenstein.ui.drawer.DrawerMenuProvider
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel
import com.lsd.wififrankenstein.ui.settings.UsbDeviceInfo
import com.lsd.wififrankenstein.ui.settings.WlanInterfaceManagerViewModel
import com.lsd.wififrankenstein.ui.updates.UpdateChecker
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.SignatureVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel by viewModels<MainViewModel>()
    private lateinit var updateChecker: UpdateChecker
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val wlanInterfaceViewModel: WlanInterfaceManagerViewModel by viewModels()

    private lateinit var navController: NavController
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var drawerAdapter: DrawerMenuAdapter

    private var exitReady = false
    private lateinit var exitCallback: OnBackPressedCallback
    private var usbWifiDialog: android.app.Dialog? = null
    private var currentNavId: Int? = null

    private fun handleStartPage() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getString("start_page", "wifi_scanner") == "all_features") {
            navController.navigate(resources.getResourceEntryName(R.id.nav_all_features)) {
                popUpTo(resources.getResourceEntryName(R.id.nav_wifi_scanner)) { inclusive = true }
            }
        }
    }

    private fun setupBackPressedHandler() {
        exitCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val navController = this@MainActivity.navController
                val navGraph = navController.graph
                val startDestinationId = navGraph.startDestinationId
                val currentDestinationId = navController.currentDestination?.id

                if (currentDestinationId != startDestinationId) {

                    isEnabled = false
                    navController.popBackStack()
                    isEnabled = true
                } else if (exitReady) {

                    finish()
                } else {

                    exitReady = true
                    Snackbar.make(
                        binding.appBarMain.root,
                        R.string.exit_app_message,
                        Snackbar.LENGTH_SHORT
                    ).show()
                    lifecycleScope.launch {
                        delay(2000)
                        exitReady = false
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            applyTheme()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error applying theme", e)
        }

        super.onCreate(savedInstanceState)

        updateChecker = UpdateChecker(applicationContext)

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

        navController = findNavController(R.id.nav_host_fragment_content_main)
        handleNotificationIntent(intent)

        setupBackPressedHandler()
        onBackPressedDispatcher.addCallback(this, exitCallback)

        drawerToggle = ActionBarDrawerToggle(
            this, drawerLayout, binding.appBarMain.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(drawerToggle)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeButtonEnabled(true)

        val headerAppName = getString(R.string.nav_header_title)
        val versionName = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }

        val modificationText = if (!SignatureVerifier.isOfficialBuild(this)) {
            val isRussian =
                ConfigurationCompat.getLocales(resources.configuration).get(0)?.language == "ru"
            val encoded = if (isRussian) {
                "0J3QtdC+0YTQuNGG0LjQsNC70YzQvdCw0Y8g0LzQvtC00LjRhNC40YbQuNGA0L7QstCw0L3QvdCw0Y8g0LLQtdGA0YHQuNGP"
            } else {
                "VW5vZmZpY2lhbCBtb2RpZmllZCB2ZXJzaW9u"
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                } else {
                    @Suppress("DEPRECATION")
                    String(
                        android.util.Base64.decode(encoded, android.util.Base64.DEFAULT),
                        Charsets.UTF_8
                    )
                }
            } catch (_: Exception) {
                "Unofficial modified version"
            }
        } else null

        val headerItems = listOf(
            DrawerItem.Header(headerAppName, versionName, modificationText)
        )

        drawerAdapter = DrawerMenuAdapter(
            onItemClick = { item ->
                currentNavId = item.navId
                drawerAdapter.currentNavId = item.navId
                try {
                    navController.navigate(item.navId)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Navigation failed", e)
                }
                drawerLayout.closeDrawers()
            },
            onCategoryToggle = { position ->
                drawerAdapter.toggleCategory(position)?.let { (categoryId, isExpanded) ->
                    persistDrawerCategoryState(categoryId, isExpanded)
                }
            }
        )

        val recyclerView = binding.drawerRecycler
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = drawerAdapter

        val menuItems = headerItems + DrawerMenuProvider.createMenu(
            getSharedPreferences("settings", MODE_PRIVATE)
                .getStringSet(KEY_DRAWER_COLLAPSED_CATEGORIES, emptySet()) ?: emptySet()
        )
        drawerAdapter.setMenu(menuItems)

        drawerAdapter.currentNavId = navController.currentDestination?.id
        currentNavId = navController.currentDestination?.id

        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentNavId = destination.id
            drawerAdapter.currentNavId = destination.id
            drawerAdapter.notifyItemRangeChanged(0, drawerAdapter.itemCount)
        }

        handleStartPage()

        settingsViewModel.enableRoot.observe(this) {
            drawerAdapter.updateRootStatus(it ?: false)
        }

        settingsViewModel.showRootWithoutRoot.observe(this) {
            drawerAdapter.updateShowWithoutRoot(it ?: false)
        }

        settingsViewModel.themeChanged.observe(this) { changed ->
            if (changed) {
                recreate()
                settingsViewModel.resetThemeChangedFlag()
            }
        }

        settingsViewModel.hasChroot.observe(this) { hc ->
            drawerAdapter.updateChrootState(hc, settingsViewModel.hasProot.value ?: false)
        }
        settingsViewModel.hasProot.observe(this) { hp ->
            drawerAdapter.updateChrootState(settingsViewModel.hasChroot.value ?: false, hp)
        }




        binding.appBarMain.fabStub.setOnInflateListener { _, inflated ->
            val fab1 =
                inflated.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(
                    R.id.fab1
                )
            val fab2 =
                inflated.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(
                    R.id.fab2
                )
            fab1.setOnClickListener { view ->
                Snackbar.make(view, getString(R.string.secondary_action_1), Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
            }
            fab2.setOnClickListener { view ->
                Snackbar.make(view, getString(R.string.secondary_action_2), Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.checkAndCopyFiles(applicationContext)
        }

        wlanInterfaceViewModel.newUsbDeviceDetected.observe(this) { device ->
            device?.let {
                showUsbWifiDetectedDialog(it)
                wlanInterfaceViewModel.clearNewUsbDeviceDetected()
            }
        }
    }

    private fun showUsbWifiDetectedDialog(device: UsbDeviceInfo) {
        val dialogBinding = DialogUsbWifiDetectedBinding.inflate(layoutInflater)

        dialogBinding.textDeviceName.text = device.deviceName
        dialogBinding.textVidPid.text = device.vidPid
        dialogBinding.textWlanInterface.text =
            device.wlanInterface ?: getString(R.string.usb_wifi_not_assigned)
        dialogBinding.textDriverName.text =
            device.driverName ?: getString(R.string.usb_wifi_not_assigned)

        val driverColor = ContextCompat.getColor(
            this,
            if (device.driverLoaded) R.color.success_green else R.color.error_red
        )
        dialogBinding.iconDriverLoaded.setImageResource(
            if (device.driverLoaded) R.drawable.ic_check_circle else R.drawable.ic_cancel
        )
        dialogBinding.iconDriverLoaded.setColorFilter(
            driverColor,
            android.graphics.PorterDuff.Mode.SRC_IN
        )

        val monitorColor = ContextCompat.getColor(
            this,
            if (device.supportsMonitorMode) R.color.success_green else R.color.error_red
        )
        dialogBinding.iconMonitorSupport.setImageResource(
            if (device.supportsMonitorMode) R.drawable.ic_check_circle else R.drawable.ic_cancel
        )
        dialogBinding.iconMonitorSupport.setColorFilter(
            monitorColor,
            android.graphics.PorterDuff.Mode.SRC_IN
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.close) { _, _ ->
                if (dialogBinding.checkboxDontShowAgain.isChecked) {
                    wlanInterfaceViewModel.dismissUsbDevice(device.vidPid)
                } else {
                    wlanInterfaceViewModel.clearNewUsbDeviceDetected()
                }
            }
            .setCancelable(false)
            .create()

        usbWifiDialog = dialog
        dialog.show()
    }

    private fun handleNotificationIntent(intent: Intent?) {
        intent?.let {
            when {
                it.getBooleanExtra("open_updates", false) -> {
                    navController.navigate(R.id.nav_updates)
                }

                it.getBooleanExtra("open_db_setup", false) -> {
                    navController.navigate(R.id.dbSetupFragment)
                }

                it.getBooleanExtra("open_airodump", false) -> {
                    if (navController.currentDestination?.id != R.id.nav_airodump) {
                        navController.navigate(R.id.nav_airodump)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        val drawerLayout = binding.drawerLayout
        return if (drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
            drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
            true
        } else {
            val currentDest = navController.currentDestination?.id
            val startDest = navController.graph.startDestinationId
            if (currentDest != startDest) {
                navController.navigateUp() || super.onSupportNavigateUp()
            } else {
                drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
                true
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerToggle.onConfigurationChanged(newConfig)
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
        wlanInterfaceViewModel.startPolling()
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "onPause called")
        wlanInterfaceViewModel.stopPolling()
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop called")
    }

    override fun onDestroy() {
        if (usbWifiDialog?.isShowing == true) {
            usbWifiDialog?.dismiss()
        }
        Log.d("MainActivity", "onDestroy called")
        super.onDestroy()
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

    private fun persistDrawerCategoryState(categoryId: Int, isExpanded: Boolean) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val collapsed =
            prefs.getStringSet(KEY_DRAWER_COLLAPSED_CATEGORIES, emptySet())!!.toMutableSet()
        val key = categoryId.toString()
        if (isExpanded) collapsed.remove(key) else collapsed.add(key)
        prefs.edit().putStringSet(KEY_DRAWER_COLLAPSED_CATEGORIES, collapsed).apply()
    }

    companion object {
        private const val KEY_DRAWER_COLLAPSED_CATEGORIES = "drawer_collapsed_categories"
    }
}