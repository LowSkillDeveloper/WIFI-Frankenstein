package com.lsd.wififrankenstein.ui.allfeatures

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentAllFeaturesBinding
import com.lsd.wififrankenstein.ui.allfeatures.AllFeaturesAdapter.ViewMode
import com.lsd.wififrankenstein.ui.drawer.DrawerItem.Requirement
import com.lsd.wififrankenstein.ui.drawer.DrawerMenuProvider
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel
import com.lsd.wififrankenstein.util.Log

class AllFeaturesFragment : Fragment() {

    private var _binding: FragmentAllFeaturesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: AllFeaturesAdapter
    private val allFeatures = createFeatureList()
    private val settingsViewModel: SettingsViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAllFeaturesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecycler()
        setupViewModeButtons()
        setupSearch()
        updateToggleStates()

        settingsViewModel.enableRoot.observe(viewLifecycleOwner) { updateAdapterState() }
        settingsViewModel.showRootWithoutRoot.observe(viewLifecycleOwner) { updateAdapterState() }
        settingsViewModel.hasChroot.observe(viewLifecycleOwner) { updateAdapterState() }
        settingsViewModel.hasProot.observe(viewLifecycleOwner) { updateAdapterState() }
    }

    private fun updateAdapterState() {
        val enableRoot = settingsViewModel.enableRoot.value ?: false
        val showWithout = settingsViewModel.showRootWithoutRoot.value ?: false
        val hasChroot = settingsViewModel.hasChroot.value ?: false
        val hasProot = settingsViewModel.hasProot.value ?: false
        adapter.menuState = DrawerMenuProvider.MenuState(
            enableRoot = enableRoot,
            showRootWithoutRoot = showWithout,
            hasChroot = hasChroot,
            hasProot = hasProot
        )
        adapter.notifyDataSetChanged()
    }

    private fun setupViewModeButtons() {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedMode = prefs.getInt("all_features_view_mode", ViewMode.GRID_COMPACT.ordinal)
        adapter.viewMode = ViewMode.entries[savedMode]
        updateLayoutManager()

        binding.buttonToggleList.setOnClickListener {
            val current = adapter.viewMode
            adapter.viewMode = when (current) {
                ViewMode.LIST_NORMAL -> ViewMode.LIST_COMPACT
                ViewMode.LIST_COMPACT -> ViewMode.LIST_NORMAL
                ViewMode.GRID -> ViewMode.GRID_COMPACT
                ViewMode.GRID_COMPACT -> ViewMode.GRID
            }
            updateLayoutManager()
            saveViewMode()
            updateToggleStates()
        }

        binding.buttonToggleGrid.setOnClickListener {
            val current = adapter.viewMode
            adapter.viewMode = when (current) {
                ViewMode.LIST_NORMAL -> ViewMode.GRID
                ViewMode.LIST_COMPACT -> ViewMode.GRID_COMPACT
                ViewMode.GRID -> ViewMode.LIST_NORMAL
                ViewMode.GRID_COMPACT -> ViewMode.LIST_COMPACT
            }
            updateLayoutManager()
            saveViewMode()
            updateToggleStates()
        }
    }

    private fun updateToggleStates() {
        val mode = adapter.viewMode
        binding.buttonToggleList.isChecked =
            mode == ViewMode.LIST_COMPACT || mode == ViewMode.GRID_COMPACT
        binding.buttonToggleGrid.isChecked = mode == ViewMode.GRID || mode == ViewMode.GRID_COMPACT
        binding.buttonToggleList.text = when (mode) {
            ViewMode.LIST_COMPACT, ViewMode.GRID_COMPACT -> getString(R.string.view_mode_list_normal)
            else -> getString(R.string.view_mode_list_compact)
        }
        binding.buttonToggleGrid.text = when (mode) {
            ViewMode.GRID, ViewMode.GRID_COMPACT -> getString(R.string.view_mode_list_normal)
            else -> getString(R.string.view_mode_grid)
        }
    }

    private fun updateLayoutManager() {
        binding.featuresRecycler.layoutManager = when (adapter.viewMode) {
            ViewMode.GRID -> GridLayoutManager(requireContext(), 2)
            ViewMode.GRID_COMPACT -> GridLayoutManager(requireContext(), 3)
            else -> LinearLayoutManager(requireContext())
        }
    }

    private fun saveViewMode() {
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("all_features_view_mode", adapter.viewMode.ordinal).apply()
    }

    private fun setupRecycler() {
        adapter = AllFeaturesAdapter { feature ->
            try {
                val route = requireContext().resources.getResourceEntryName(feature.navId)
                findNavController().navigate(route)
            } catch (e: Exception) {
                Log.w("AllFeaturesFragment", "Navigation failed for ${feature.navId}", e)
            }
        }
        updateLayoutManager()
        binding.featuresRecycler.adapter = adapter
        adapter.submitList(allFeatures)
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                binding.clearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                filterFeatures(query)
            }
        })

        binding.clearSearch.setOnClickListener {
            binding.searchInput.text?.clear()
        }
    }

    private fun filterFeatures(query: String) {
        adapter.submitList(
            if (query.isEmpty()) allFeatures
            else allFeatures.filter { item ->
                val title = getString(item.titleRes).lowercase()
                val desc = getString(item.descriptionRes).lowercase()
                title.contains(query) || desc.contains(query)
            }
        )
    }

    private fun createFeatureList(): List<FeatureItem> {
        return listOf(
            FeatureItem(
                R.string.menu_wifi_scanner,
                R.string.feature_desc_wifi_scanner,
                R.drawable.baseline_wifi_find_24,
                R.id.nav_wifi_scanner,
                FeatureCategory.CORE_TOOLS
            ),
            FeatureItem(
                R.string.menu_database_finder,
                R.string.feature_desc_database_finder,
                R.drawable.database_search_24px,
                R.id.nav_database_finder,
                FeatureCategory.CORE_TOOLS
            ),
            FeatureItem(
                R.string.menu_wifi_map,
                R.string.feature_desc_wifi_map,
                R.drawable.ic_menu_mapmode,
                R.id.nav_wifi_map,
                FeatureCategory.CORE_TOOLS
            ),
            FeatureItem(
                R.string.menu_mac_location,
                R.string.feature_desc_mac_location,
                R.drawable.ic_location,
                R.id.nav_mac_location,
                FeatureCategory.CORE_TOOLS
            ),
            FeatureItem(
                R.string.menu_api_query,
                R.string.feature_desc_api_query,
                R.drawable.outline_arrow_upload_ready_24,
                R.id.nav_api_query,
                FeatureCategory.API_NETWORK
            ),
            FeatureItem(
                R.string.menu_upload_routerscan,
                R.string.feature_desc_upload_routerscan,
                R.drawable.cloud_download_24px,
                R.id.nav_upload_routerscan,
                FeatureCategory.API_NETWORK
            ),
            FeatureItem(
                R.string.menu_router_scan,
                R.string.feature_desc_router_scan,
                R.drawable.router_24px,
                R.id.nav_router_scan,
                FeatureCategory.API_NETWORK,
                Requirement.PROOT_CHROOT
            ),
            FeatureItem(
                R.string.menu_iw_wifi_scanner,
                R.string.feature_desc_iw_wifi,
                R.drawable.android_wifi_3_bar_plus_24px,
                R.id.nav_iw_wifi_scanner,
                FeatureCategory.ROOT_FUNCTIONS,
                Requirement.ROOT
            ),
            FeatureItem(
                R.string.menu_saved_passwords,
                R.string.feature_desc_saved_passwords,
                R.drawable.ic_file,
                R.id.nav_saved_passwords,
                FeatureCategory.ROOT_FUNCTIONS,
                Requirement.ROOT
            ),
            FeatureItem(
                R.string.menu_pixiedust,
                R.string.feature_desc_pixiedust,
                R.drawable.ic_key,
                R.id.nav_pixie_dust,
                FeatureCategory.ROOT_FUNCTIONS,
                Requirement.ROOT
            ),
            FeatureItem(
                R.string.menu_bruteforce,
                R.string.feature_desc_bruteforce,
                R.drawable.ic_lock_open,
                R.id.nav_bruteforce,
                FeatureCategory.ROOT_FUNCTIONS
            ),
            FeatureItem(
                R.string.menu_wpa_cracker,
                R.string.feature_desc_wpa_cracker,
                R.drawable.ic_lock_open,
                R.id.nav_wpa_cracker,
                FeatureCategory.ROOT_FUNCTIONS
            ),
            FeatureItem(
                R.string.menu_handshake_capture_selector,
                R.string.feature_desc_handshake_capture,
                R.drawable.grid_3x3_24px,
                R.id.nav_handshake_capture_selector,
                FeatureCategory.ROOT_FUNCTIONS,
                Requirement.CHROOT
            ),
            FeatureItem(
                R.string.handshake_storage_title,
                R.string.feature_desc_handshake_converter,
                R.drawable.home_storage_24px,
                R.id.nav_handshake_storage,
                FeatureCategory.ROOT_FUNCTIONS
            ),
            FeatureItem(
                R.string.menu_handshake_converter,
                R.string.feature_desc_handshake_converter,
                R.drawable.swap_horizontal_circle_24px,
                R.id.nav_handshake_converter,
                FeatureCategory.ROOT_FUNCTIONS
            ),
            FeatureItem(
                R.string.menu_wps_generator,
                R.string.feature_desc_wps_generator,
                R.drawable.ic_key,
                R.id.nav_wps_generator,
                FeatureCategory.GENERATORS
            ),
            FeatureItem(
                R.string.wpa_generator_title,
                R.string.feature_desc_wpa_generator,
                R.drawable.ic_lock_open,
                R.id.nav_wpa_generator,
                FeatureCategory.GENERATORS
            ),
            FeatureItem(
                R.string.menu_wifi_analysis,
                R.string.feature_desc_wifi_analysis,
                R.drawable.ic_wifi,
                R.id.nav_wifi_analysis,
                FeatureCategory.UTILITIES
            ),
            FeatureItem(
                R.string.menu_qr_generator,
                R.string.feature_desc_qr_generator,
                R.drawable.ic_qr_code,
                R.id.nav_qr_generator,
                FeatureCategory.UTILITIES
            ),
            FeatureItem(
                R.string.menu_local_network,
                R.string.feature_desc_local_network,
                R.drawable.ic_layers,
                R.id.nav_local_network,
                FeatureCategory.NETWORK_DIAGNOSTICS
            ),
            FeatureItem(
                R.string.menu_internet_blocking,
                R.string.feature_desc_internet_blocking,
                R.drawable.vpn_lock_2_24px,
                R.id.nav_internet_blocking,
                FeatureCategory.NETWORK_DIAGNOSTICS
            ),
            FeatureItem(
                R.string.menu_in_app_database,
                R.string.feature_desc_in_app_database,
                R.drawable.ic_database,
                R.id.nav_in_app_database,
                FeatureCategory.OTHER
            ),
            FeatureItem(
                R.string.menu_updates,
                R.string.feature_desc_updates,
                R.drawable.outline_archive_24,
                R.id.nav_updates,
                FeatureCategory.OTHER
            ),
            FeatureItem(
                R.string.menu_settings,
                R.string.feature_desc_settings,
                R.drawable.outline_build_circle_24,
                R.id.nav_settings,
                FeatureCategory.OTHER
            ),
            FeatureItem(
                R.string.menu_about,
                R.string.feature_desc_about,
                R.drawable.ic_info,
                R.id.nav_about,
                FeatureCategory.OTHER
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
