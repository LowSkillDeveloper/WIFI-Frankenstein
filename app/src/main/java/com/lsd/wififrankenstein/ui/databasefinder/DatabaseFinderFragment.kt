package com.lsd.wififrankenstein.ui.databasefinder

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentDatabaseFinderBinding
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DatabaseFinderFragment : Fragment() {

    private var _binding: FragmentDatabaseFinderBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DatabaseFinderViewModel by activityViewModels()
    private lateinit var searchResultsAdapter: SearchResultsAdapter
    private var searchStatusSnackbar: Snackbar? = null

    companion object {
        private const val TAG = "DatabaseFinderFragment"
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

        viewModel.wpaSecToast.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                viewModel.clearWpaSecToast()
            }
        }

        binding.searchModeToggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnSearchExact -> SearchMode.EXACT
                    R.id.btnSearchPrefix -> SearchMode.PREFIX
                    R.id.btnSearchContains -> SearchMode.SUBSTRING
                    else -> return@addOnButtonCheckedListener
                }
                viewModel.setSearchMode(mode)
                if (mode == SearchMode.SUBSTRING && viewModel.isSlowSearchPotential()) {
                    Snackbar.make(
                        binding.root,
                        R.string.search_large_db_warning,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }

        setupRecyclerView()
        setupSearchButton()
        setupSourcesButton()
        setupFiltersButton()
        setupDbSettingsButton()
        setupAdvancedSearchToggle()
        setupScrollToTop()
        requestPermissions()
    }

    private fun setupDbSettingsButton() {
        binding.buttonDbSettings.setOnClickListener {
            val bottomSheetDialog = BottomSheetDialog(requireContext())
            val bottomSheetBinding =
                com.lsd.wififrankenstein.databinding.BottomSheetDbSettingsBinding.inflate(
                    layoutInflater
                )
            bottomSheetDialog.setContentView(bottomSheetBinding.root)

            bottomSheetBinding.buttonDbSetup.setOnClickListener {
                findNavController().navigate(R.id.action_databaseFinderFragment_to_dbSetupFragment)
                bottomSheetDialog.dismiss()
            }

            bottomSheetBinding.buttonInAppDatabase.setOnClickListener {
                findNavController().navigate(R.id.nav_in_app_database)
                bottomSheetDialog.dismiss()
            }

            bottomSheetDialog.show()
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(
                requireContext(),
                it
            ) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsToRequest,
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun setupRecyclerView() {
        searchResultsAdapter = SearchResultsAdapter(
            onCheckWpaSec = { item -> viewModel.checkOnWpaSec(item) },
            sourceLabelResolver = { source -> formatSourceLabel(source) }
        )
        binding.recyclerViewResults.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = searchResultsAdapter
        }

        searchResultsAdapter.addLoadStateListener { loadState ->
            if (_binding == null) return@addLoadStateListener

            val isLoading = loadState.source.refresh is LoadState.Loading ||
                    loadState.source.append is LoadState.Loading

            if (isLoading) {
                binding.progressBarDatabaseCheck.visibility = View.VISIBLE
                binding.progressBarDatabaseCheck.startAnimation()
                showCancellableSearchBar()
            } else {
                binding.progressBarDatabaseCheck.stopAnimation()
                binding.progressBarDatabaseCheck.visibility = View.GONE
                searchStatusSnackbar?.dismiss()
                searchStatusSnackbar = null
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

    private fun formatSourcePath(path: String): String {
        return try {
            when {
                path.startsWith("content://") -> {
                    val uri = android.net.Uri.parse(path)
                    uri.lastPathSegment?.let { lastSegment ->
                        val decodedSegment = android.net.Uri.decode(lastSegment)
                        decodedSegment.substringAfterLast('/')
                    } ?: path
                }

                path.startsWith("file://") -> {
                    val uri = android.net.Uri.parse(path)
                    uri.lastPathSegment ?: path
                }

                path == "local_db" -> getString(R.string.local_database)
                else -> {
                    path.substringAfterLast('/')
                }
            }.substringAfterLast("%2F")
        } catch (e: Exception) {
            Log.e(TAG, "Error formatting source path: $path", e)
            path
        }
    }

    private fun formatSourceLabel(source: String): String {
        val dbItem = viewModel.dbSetupViewModel.dbList.value?.find { it.id == source }
        if (dbItem != null) {
            val base = formatSourcePath(dbItem.path)
            return if (!dbItem.tableName.isNullOrBlank()) "$base · ${dbItem.tableName}" else base
        }
        return formatSourcePath(source)
    }

    private fun setupSearchButton() {
        binding.buttonSearch.setOnClickListener {
            val query = binding.editTextSearch.text.toString()
            Log.d("DatabaseFinderFragment", "Search button clicked with query: $query")
            if (viewModel.isSlowSearchPotential()) {
                Snackbar.make(binding.root, R.string.search_large_db_hint, Snackbar.LENGTH_LONG)
                    .show()
            }
            viewModel.performSearch(query)
        }
    }

    private fun showCancellableSearchBar() {
        if (!viewModel.isSlowSearchPotential()) return
        if (searchStatusSnackbar != null) return
        searchStatusSnackbar = Snackbar.make(
            binding.root,
            R.string.search_large_db_hint,
            Snackbar.LENGTH_INDEFINITE
        ).setAction(R.string.cancel) {
            viewModel.cancelSearch()
        }
        searchStatusSnackbar?.show()
    }

    private fun setupSourcesButton() {
        binding.buttonSources.setOnClickListener {
            showCustomChecklistBottomSheet(
                title = getString(R.string.select_sources),
                items = viewModel.getAvailableSources(),
                selectedItems = viewModel.getSelectedSources().toSet(),
                formatItem = { formatSourceLabel(it) },
                onToggle = { item, isChecked -> viewModel.setSourceSelected(item, isChecked) }
            )
        }
    }

    private fun setupFiltersButton() {
        binding.buttonFilters.setOnClickListener {
            val filters = FilterType.entries
            val filterLabels = filters.map { getString(it.labelRes) }
            val selectedFilters =
                viewModel.getSelectedFilters().map { getString(it.labelRes) }.toSet()

            showCustomChecklistBottomSheet(
                title = getString(R.string.select_filters),
                items = filterLabels,
                selectedItems = selectedFilters,
                formatItem = { it },
                onToggle = { item, isChecked ->
                    val index = filterLabels.indexOf(item)
                    if (index >= 0) viewModel.setFilterSelected(filters[index], isChecked)
                }
            )
        }
    }

    private fun showCustomChecklistBottomSheet(
        title: String,
        items: List<String>,
        selectedItems: Set<String>,
        formatItem: (String) -> String,
        onToggle: (String, Boolean) -> Unit
    ) {
        val context = requireContext()
        val dialog = BottomSheetDialog(context)
        val primaryColor = resolveColor(android.R.attr.colorPrimary)
        val onSurfaceColor = resolveColor(com.google.android.material.R.attr.colorOnSurface)

        val rootLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.text_secondary))
            rootLayout.addView(this)
        }

        val cardView = com.google.android.material.card.MaterialCardView(context).apply {
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            setStrokeColor(primaryColor)
            strokeWidth = dp(1)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(16), 0, dp(16), dp(16))
            layoutParams = lp
        }

        val container = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }

        container.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryColor)
            setPadding(dp(16), dp(12), dp(16), dp(8))
        })

        View(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(dp(16), 0, dp(16), 0)
                }
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider_color))
            container.addView(this)
        }

        val selectedState = selectedItems.toMutableSet()

        items.forEachIndexed { index, item ->
            val checkBox = MaterialCheckBox(context).apply {
                text = formatItem(item)
                textSize = 14f
                isChecked = item in selectedState
                setTextColor(onSurfaceColor)
                minimumHeight = dp(48)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                val typedValue = TypedValue()
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground,
                    typedValue,
                    true
                )
                setBackgroundResource(typedValue.resourceId)
                isClickable = true
                isFocusable = true
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedState.add(item) else selectedState.remove(item)
                    onToggle(item, isChecked)
                }
            }
            container.addView(checkBox)

            if (index < items.size - 1) {
                View(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                            setMargins(dp(16), 0, dp(16), 0)
                        }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider_color))
                    container.addView(this)
                }
            }
        }

        val btnDone = MaterialButton(context).apply {
            text = getString(R.string.ok)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(16), dp(12), dp(16), dp(8))
            }
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(btnDone)

        cardView.addView(container)
        rootLayout.addView(cardView)
        dialog.setContentView(rootLayout)
        dialog.show()
    }

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(
            requireContext(),
            tv.resourceId
        ) else tv.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private var isAdvancedMode = false

    private fun setupAdvancedSearchToggle() {
        binding.modeToggleGroup.check(R.id.buttonSimpleMode)

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isAdvancedMode = checkedId == R.id.buttonAdvancedMode
                updateSearchModeUI()
            }
        }

        binding.buttonAdvancedSearch.setOnClickListener {
            performAdvancedSearch()
        }

        setupWildcardButtons()
    }

    private fun updateSearchModeUI() {
        if (isAdvancedMode) {
            binding.simpleSearchContainer.visibility = View.GONE
            binding.advancedSearchContainer.visibility = View.VISIBLE
            binding.searchModeToggleGroup.visibility = View.GONE
            binding.buttonFilters.visibility = View.GONE
        } else {
            binding.simpleSearchContainer.visibility = View.VISIBLE
            binding.advancedSearchContainer.visibility = View.GONE
            binding.searchModeToggleGroup.visibility = View.VISIBLE
            binding.buttonFilters.visibility = View.VISIBLE
        }
    }

    private fun setupWildcardButtons() {
        binding.buttonBssidWildcard.setOnClickListener {
            insertTextAtCursor(binding.editTextBssid, "*")
        }

        binding.buttonEssidAnyChar.setOnClickListener {
            insertTextAtCursor(binding.editTextEssid, "□")
        }

        binding.buttonEssidAnyString.setOnClickListener {
            insertTextAtCursor(binding.editTextEssid, "◯")
        }

        binding.buttonPasswordAnyChar.setOnClickListener {
            insertTextAtCursor(binding.editTextPassword, "□")
        }

        binding.buttonPasswordAnyString.setOnClickListener {
            insertTextAtCursor(binding.editTextPassword, "◯")
        }

        binding.buttonWpsPinAnyChar.setOnClickListener {
            insertTextAtCursor(binding.editTextWpsPin, "□")
        }

        binding.buttonWpsPinAnyString.setOnClickListener {
            insertTextAtCursor(binding.editTextWpsPin, "◯")
        }
    }

    private fun insertTextAtCursor(
        editText: com.google.android.material.textfield.TextInputEditText,
        text: String
    ) {
        val start = editText.selectionStart
        val end = editText.selectionEnd
        val currentText = editText.text?.toString() ?: ""

        val newText = StringBuilder(currentText)
            .replace(start, end, text)
            .toString()

        editText.setText(newText)
        editText.setSelection(start + text.length)
    }

    private fun performAdvancedSearch() {
        val advancedQuery = AdvancedSearchQuery(
            bssid = binding.editTextBssid.text.toString().trim(),
            essid = binding.editTextEssid.text.toString().trim(),
            password = binding.editTextPassword.text.toString().trim(),
            wpsPin = binding.editTextWpsPin.text.toString().trim(),
            caseSensitive = binding.checkBoxCaseSensitive.isChecked
        )

        if (!advancedQuery.hasContent()) {
            return
        }

        Log.d("DatabaseFinderFragment", "Advanced search: $advancedQuery")
        viewModel.performAdvancedSearch(advancedQuery)
    }

    private fun setupScrollToTop() {
        binding.fabScrollToTop.setOnClickListener {
            binding.appBarLayout.setExpanded(true, true)
            binding.recyclerViewResults.smoothScrollToPosition(0)
        }

        binding.recyclerViewResults.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                super.onScrolled(recyclerView, dx, dy)

                val layoutManager =
                    recyclerView.layoutManager as? LinearLayoutManager
                val firstVisiblePosition = layoutManager?.findFirstVisibleItemPosition() ?: 0

                if (dy < 0 && firstVisiblePosition <= 2) {
                    binding.appBarLayout.setExpanded(true, true)
                }

                if (firstVisiblePosition > 2) {
                    binding.fabScrollToTop.show()
                } else {
                    binding.fabScrollToTop.hide()
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchStatusSnackbar?.dismiss()
        searchStatusSnackbar = null
        _binding = null
    }
}