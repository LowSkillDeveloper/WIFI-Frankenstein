package com.lsd.wififrankenstein.ui.databasefinder

import android.Manifest
import android.content.ContentValues.TAG
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentDatabaseFinderBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DatabaseFinderFragment : Fragment() {

    private var _binding: FragmentDatabaseFinderBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DatabaseFinderViewModel by viewModels()
    private lateinit var searchResultsAdapter: SearchResultsAdapter


    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDatabaseFinderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.refreshDatabases()

        binding.progressBarDatabaseCheck.visibility = View.GONE

        if (DbSetupViewModel.needDataRefresh) {
            viewModel.refreshDatabases()
        }

        viewModel.isSearching.observe(viewLifecycleOwner) { isSearching ->
            if (isSearching) {
                binding.progressBarDatabaseCheck.visibility = View.VISIBLE
                binding.progressBarDatabaseCheck.startAnimation()
            } else {
                binding.progressBarDatabaseCheck.stopAnimation()
            }
        }

        binding.checkBoxWholeWord.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setSearchWholeWords(isChecked)
            Log.d("DatabaseFinderFragment", "Whole Words: $isChecked")
        }

        setupRecyclerView()
        setupSearchButton()
        setupSourcesButton()
        setupFiltersButton()
        setupDbSettingsButton()
        requestPermissions()
    }

    private fun setupDbSettingsButton() {
        binding.buttonDbSettings.setOnClickListener {
            findNavController().navigate(R.id.action_databaseFinderFragment_to_dbSetupFragment)
        }
    }
    private fun requestPermissions() {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(requireActivity(), permissionsToRequest, PERMISSION_REQUEST_CODE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
        }
    }

    private fun setupRecyclerView() {
        searchResultsAdapter = SearchResultsAdapter()
        binding.recyclerViewResults.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = searchResultsAdapter
        }

        searchResultsAdapter = SearchResultsAdapter()
        binding.recyclerViewResults.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = searchResultsAdapter
        }

        searchResultsAdapter.addLoadStateListener { loadState ->

            val isLoading = loadState.source.refresh is LoadState.Loading ||
                    loadState.source.append is LoadState.Loading

            if (isLoading) {
                binding.progressBarDatabaseCheck.visibility = View.VISIBLE
                binding.progressBarDatabaseCheck.startAnimation()
            } else {
                binding.progressBarDatabaseCheck.stopAnimation()
            }

            val errorState = loadState.source.refresh as? LoadState.Error
                ?: loadState.source.append as? LoadState.Error
                ?: loadState.source.prepend as? LoadState.Error

            errorState?.let {
                Log.e(TAG, "Ошибка загрузки данных: ${it.error}")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.searchResults.collectLatest { pagingData ->
                if (_binding != null) {
                    searchResultsAdapter.submitData(pagingData)
                }
            }
        }
    }

    private fun setupSearchButton() {
        binding.buttonSearch.setOnClickListener {
            val query = binding.editTextSearch.text.toString()
            Log.d("DatabaseFinderFragment", "Search button clicked with query: $query")
            viewModel.performSearch(query)
        }
    }

    private fun setupSourcesButton() {
        binding.buttonSources.setOnClickListener {
            val sources = viewModel.getAvailableSources()
            val selectedSources = viewModel.getSelectedSources()
            Log.d(TAG, "Available sources: ${sources.joinToString()}")
            Log.d(TAG, "Selected sources: ${selectedSources.joinToString()}")

            val checkedItems = sources.map { it in selectedSources }.toBooleanArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.select_sources)
                .setMultiChoiceItems(sources.toTypedArray(), checkedItems) { _, which, isChecked ->
                    viewModel.setSourceSelected(sources[which], isChecked)
                    Log.d(TAG, "Source ${sources[which]} ${if (isChecked) "selected" else "unselected"}")
                }
                .setPositiveButton(R.string.ok) { _, _ ->
                    Log.d(TAG, "Sources dialog closed, selected sources: ${viewModel.getSelectedSources().joinToString()}")
                }
                .show()
        }
    }

    private fun setupFiltersButton() {
        binding.buttonFilters.setOnClickListener {
            val filters = listOf(
                R.string.filter_bssid,
                R.string.filter_essid,
                R.string.filter_wifi_password,
                R.string.filter_wps_pin
            )
            val selectedFilters = viewModel.getSelectedFilters()
            val checkedItems = filters.map { it in selectedFilters }.toBooleanArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.select_filters)
                .setMultiChoiceItems(filters.map { getString(it) }.toTypedArray(), checkedItems) { _, which, isChecked ->
                    viewModel.setFilterSelected(filters[which], isChecked)
                }
                .setPositiveButton(R.string.ok) { _, _ -> }
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}