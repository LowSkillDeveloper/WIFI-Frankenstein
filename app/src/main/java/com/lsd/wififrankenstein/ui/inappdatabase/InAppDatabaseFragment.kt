package com.lsd.wififrankenstein.ui.inappdatabase

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.BottomSheetDatabaseManagementBinding
import com.lsd.wififrankenstein.databinding.FragmentInAppDatabaseBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InAppDatabaseFragment : Fragment() {

    private var _binding: FragmentInAppDatabaseBinding? = null
    private val binding get() = _binding!!
    private val viewModel: InAppDatabaseViewModel by viewModels()
    private lateinit var adapter: DatabaseRecordsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInAppDatabaseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearch()
        setupFab()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = DatabaseRecordsAdapter { record ->
            viewModel.showRecordDetails(record)
        }

        binding.recyclerViewRecords.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@InAppDatabaseFragment.adapter
        }

        adapter.addLoadStateListener { loadState ->
            binding.swipeRefreshLayout.isRefreshing = loadState.source.refresh is LoadState.Loading

            val isEmpty = loadState.source.refresh is LoadState.NotLoading && adapter.itemCount == 0
            binding.layoutEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.recyclerViewRecords.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }

        lifecycleScope.launch {
            viewModel.records.collectLatest { pagingData ->
                adapter.submitData(pagingData)
            }
        }
    }

    private fun setupSearch() {
        binding.buttonSearch.setOnClickListener {
            performSearch()
        }

        binding.editTextSearch.setOnEditorActionListener { _, _, _ ->
            performSearch()
            true
        }
    }

    private fun performSearch() {
        val query = binding.editTextSearch.text?.toString()?.trim() ?: ""
        val filterName = binding.chipFilterName.isChecked
        val filterMac = binding.chipFilterMac.isChecked
        val filterPassword = binding.chipFilterPassword.isChecked
        val filterWps = binding.chipFilterWps.isChecked

        viewModel.updateSearch(query, filterName, filterMac, filterPassword, filterWps)
    }

    private fun setupFab() {
        binding.fabManage.setOnClickListener {
            showManagementBottomSheet()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            adapter.refresh()
        }
    }

    private fun showManagementBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(requireContext())
        val bottomSheetBinding = BottomSheetDatabaseManagementBinding.inflate(layoutInflater)
        bottomSheetDialog.setContentView(bottomSheetBinding.root)

        bottomSheetBinding.switchIndexing.isChecked = viewModel.isIndexingEnabled()

        bottomSheetBinding.switchIndexing.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewModel.enableIndexing()
            } else {
                viewModel.disableIndexing()
            }
            updateStats()
        }

        bottomSheetBinding.buttonOptimize.setOnClickListener {
            viewModel.optimizeDatabase()
            bottomSheetDialog.dismiss()
            showSnackbar(getString(R.string.database_optimized))
            updateStats()
        }

        bottomSheetBinding.buttonRemoveDuplicates.setOnClickListener {
            viewModel.removeDuplicates()
            bottomSheetDialog.dismiss()
            showSnackbar(getString(R.string.duplicates_removed))
            adapter.refresh()
            updateStats()
        }

        bottomSheetBinding.buttonImport.setOnClickListener {
            bottomSheetDialog.dismiss()
            showImportDialog()
        }

        bottomSheetBinding.buttonExport.setOnClickListener {
            bottomSheetDialog.dismiss()
            showExportDialog()
        }

        bottomSheetBinding.buttonBackup.setOnClickListener {
            bottomSheetDialog.dismiss()
            selectBackupLocation()
        }

        bottomSheetBinding.buttonRestore.setOnClickListener {
            bottomSheetDialog.dismiss()
            selectRestoreFile()
        }

        bottomSheetBinding.buttonClearDatabase.setOnClickListener {
            bottomSheetDialog.dismiss()
            showClearDatabaseDialog()
        }

        bottomSheetDialog.show()
    }

    private fun showImportDialog() {
        val formats = arrayOf("JSON", "CSV", "TXT (RouterScan)")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_format)
            .setItems(formats) { _, which ->
                when (which) {
                    0 -> startFileSelection("application/json", REQUEST_IMPORT_JSON)
                    1 -> startFileSelection("text/csv", REQUEST_IMPORT_CSV)
                    2 -> startFileSelection("text/plain", REQUEST_IMPORT_ROUTERSCAN)
                }
            }
            .show()
    }

    private fun showExportDialog() {
        val formats = arrayOf("JSON", "CSV")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_format)
            .setItems(formats) { _, which ->
                when (which) {
                    0 -> startFileCreation("wifi_database.json", "application/json", REQUEST_EXPORT_JSON)
                    1 -> startFileCreation("wifi_database.csv", "text/csv", REQUEST_EXPORT_CSV)
                }
            }
            .show()
    }

    private fun showClearDatabaseDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.warning)
            .setMessage(R.string.clear_database_warning)
            .setPositiveButton(R.string.yes) { _, _ ->
                viewModel.clearDatabase()
                adapter.refresh()
                updateStats()
                showSnackbar(getString(R.string.database_cleared))
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun startFileCreation(fileName: String, mimeType: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        startActivityForResult(intent, requestCode)
    }

    private fun startFileSelection(mimeType: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when (mimeType) {
                "text/csv" -> "*/*"
                else -> mimeType
            }
            if (mimeType == "text/csv") {
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "application/csv"))
            }
        }
        startActivityForResult(intent, requestCode)
    }

    private fun selectBackupLocation() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-sqlite3"
            putExtra(Intent.EXTRA_TITLE, "local_wifi_database_backup.db")
        }
        startActivityForResult(intent, REQUEST_BACKUP_DB)
    }

    private fun selectRestoreFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-sqlite3", "application/octet-stream", "application/vnd.sqlite3"))
        }
        startActivityForResult(intent, REQUEST_RESTORE_DB)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && data?.data != null) {
            val uri = data.data!!
            when (requestCode) {
                REQUEST_EXPORT_JSON -> {
                    viewModel.exportToJson(uri)
                    showSnackbar(getString(R.string.export_successful))
                }
                REQUEST_EXPORT_CSV -> {
                    viewModel.exportToCsv(uri)
                    showSnackbar(getString(R.string.export_successful))
                }
                REQUEST_IMPORT_JSON -> {
                    viewModel.importFromJson(uri)
                    adapter.refresh()
                    updateStats()
                    showSnackbar(getString(R.string.import_successful))
                }
                REQUEST_IMPORT_CSV -> {
                    viewModel.importFromCsv(uri)
                    adapter.refresh()
                    updateStats()
                    showSnackbar(getString(R.string.import_successful))
                }
                REQUEST_IMPORT_ROUTERSCAN -> {
                    viewModel.importFromRouterScan(uri)
                    adapter.refresh()
                    updateStats()
                    showSnackbar(getString(R.string.import_successful))
                }
                REQUEST_BACKUP_DB -> {
                    viewModel.exportDatabase(uri)
                    showSnackbar(getString(R.string.database_backed_up))
                }
                REQUEST_RESTORE_DB -> {
                    viewModel.restoreDatabaseFromUri(uri)
                    adapter.refresh()
                    updateStats()
                    showSnackbar(getString(R.string.database_restored))
                }
            }
        }
    }

    private fun observeViewModel() {
        viewModel.databaseStats.observe(viewLifecycleOwner) { stats ->
            binding.textViewDbStats.text = stats
        }

        viewModel.indexStatus.observe(viewLifecycleOwner) { status ->
            binding.textViewIndexStatus.text = getString(R.string.indexing_status, status)
        }
    }

    private fun updateStats() {
        viewModel.updateStats()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateStats()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val REQUEST_EXPORT_JSON = 1001
        private const val REQUEST_EXPORT_CSV = 1002
        private const val REQUEST_IMPORT_JSON = 1003
        private const val REQUEST_IMPORT_CSV = 1004
        private const val REQUEST_IMPORT_ROUTERSCAN = 1005
        private const val REQUEST_BACKUP_DB = 1006
        private const val REQUEST_RESTORE_DB = 1007
    }
}