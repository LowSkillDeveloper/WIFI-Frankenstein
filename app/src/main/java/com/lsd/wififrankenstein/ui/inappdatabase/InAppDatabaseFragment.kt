package com.lsd.wififrankenstein.ui.inappdatabase

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.BottomSheetDatabaseManagementBinding
import com.lsd.wififrankenstein.databinding.FragmentInAppDatabaseBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.util.QuadkeyUtils
import com.lsd.wififrankenstein.util.ThreeWiFiCsvRow
import com.lsd.wififrankenstein.util.ThreeWiFiUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InAppDatabaseFragment : Fragment() {

    private var _binding: FragmentInAppDatabaseBinding? = null
    private val binding get() = _binding!!
    private val viewModel: InAppDatabaseViewModel by viewModels()
    private val dbSetupViewModel: DbSetupViewModel by viewModels()
    private lateinit var adapter: DatabaseRecordsAdapter

    private var progressDialog: androidx.appcompat.app.AlertDialog? = null
    private var isBackupBeforeClear = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInAppDatabaseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearch()
        setupSearchTooltip()
        setupFab()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = DatabaseRecordsAdapter(
            onItemClick = { record ->
                viewModel.showRecordDetails(record)
            },
            onItemEdit = { record ->
                showEditRecordDialog(record)
            },
            onItemDelete = { record ->
                showDeleteConfirmation(record)
            },
            onUploadTo3WiFi = { record ->
                uploadRecordTo3WiFi(listOf(record))
            },
            onCheckWpaSec = { record ->
                viewModel.checkOnWpaSec(record)
            }
        )

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

        adapter.addLoadStateListener { loadState ->
            binding.swipeRefreshLayout.isRefreshing = loadState.source.refresh is LoadState.Loading

            val isLoading = loadState.source.refresh is LoadState.Loading
            if (isLoading) {
                binding.progressBarDatabaseCheck.startAnimation()
            } else {
                binding.progressBarDatabaseCheck.stopAnimation()
            }

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

    private fun showAddRecordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_record, null)
        setupRecordDialog(dialogView, null)
    }

    private fun showEditRecordDialog(record: WifiNetwork) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_record, null)
        setupRecordDialog(dialogView, record)
    }

    private fun setupRecordDialog(dialogView: View, record: WifiNetwork?) {
        val etWifiName = dialogView.findViewById<TextInputEditText>(R.id.etWifiName)
        val etMacAddress = dialogView.findViewById<TextInputEditText>(R.id.etMacAddress)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.etPassword)
        val etWpsPin = dialogView.findViewById<TextInputEditText>(R.id.etWpsPin)
        val etAdminPanel = dialogView.findViewById<TextInputEditText>(R.id.etAdminPanel)
        val etLatitude = dialogView.findViewById<TextInputEditText>(R.id.etLatitude)
        val etLongitude = dialogView.findViewById<TextInputEditText>(R.id.etLongitude)
        val etQuadkey = dialogView.findViewById<TextInputEditText>(R.id.etQuadkey)

        etQuadkey.isEnabled = false
        etQuadkey.isFocusable = false
        etQuadkey.isFocusableInTouchMode = false
        etQuadkey.isClickable = false

        var positiveButton: android.widget.Button? = null

        val updateQuadkey = {
            val lat = etLatitude.text.toString().toDoubleOrNull()
            val lon = etLongitude.text.toString().toDoubleOrNull()
            val latEmpty = etLatitude.text.toString().isEmpty()
            val lonEmpty = etLongitude.text.toString().isEmpty()

            if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                val qk = QuadkeyUtils.latLonToQuadkey(lat, lon)
                etQuadkey.setText(qk.toString())
                positiveButton?.isEnabled = true
            } else if (latEmpty && lonEmpty) {
                etQuadkey.setText("")
                positiveButton?.isEnabled = true
            } else {
                etQuadkey.setText("")
                positiveButton?.isEnabled = false
            }
        }

        val quadkeyWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun afterTextChanged(p0: android.text.Editable?) {
                updateQuadkey()
            }
        }
        etLatitude.addTextChangedListener(quadkeyWatcher)
        etLongitude.addTextChangedListener(quadkeyWatcher)

        record?.let {
            etWifiName.setText(it.wifiName)
            etMacAddress.setText(it.macAddress)
            etPassword.setText(it.wifiPassword)
            etWpsPin.setText(it.wpsCode)
            etAdminPanel.setText(it.adminPanel)
            etLatitude.setText(it.latitude?.toString() ?: "")
            etLongitude.setText(it.longitude?.toString() ?: "")
        }

        val title = if (record == null) R.string.add_new_record else R.string.edit_record
        val positiveButtonId = if (record == null) R.string.add else R.string.update

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(positiveButtonId) { _, _ ->
                val newRecord = WifiNetwork(
                    id = record?.id ?: 0,
                    wifiName = etWifiName.text.toString(),
                    macAddress = etMacAddress.text.toString(),
                    wifiPassword = etPassword.text.toString().takeIf { it.isNotBlank() },
                    wpsCode = etWpsPin.text.toString().takeIf { it.isNotBlank() },
                    adminPanel = etAdminPanel.text.toString().takeIf { it.isNotBlank() },
                    latitude = etLatitude.text.toString().toDoubleOrNull(),
                    longitude = etLongitude.text.toString().toDoubleOrNull()
                )

                if (record == null) {
                    viewModel.addRecord(newRecord)
                } else {
                    viewModel.updateRecord(newRecord)
                }
                adapter.refresh()
                updateStats()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()

        positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        updateQuadkey()
    }

    private fun showDeleteConfirmation(record: WifiNetwork) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_record)
            .setMessage(R.string.delete_record_confirmation)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteRecord(record)
                adapter.refresh()
                updateStats()
                showSnackbar(getString(R.string.record_deleted))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private fun setupSearchTooltip() {
        val prefs = requireContext().getSharedPreferences("in_app_db", Context.MODE_PRIVATE)
        if (prefs.getBoolean("search_tooltip_dismissed", false)) return

        val view =
            layoutInflater.inflate(R.layout.card_search_tooltip, binding.tooltipContainer, false)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonTooltipOk)
            .setOnClickListener {
                binding.tooltipContainer.removeAllViews()
                prefs.edit().putBoolean("search_tooltip_dismissed", true).apply()
            }

        val params = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(dp(16), dp(8), dp(16), dp(8))
        view.layoutParams = params
        binding.tooltipContainer.addView(view)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
        binding.fabAdd.setOnClickListener {
            showAddRecordDialog()
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
                showIndexLevelSelectionDialog()
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

        bottomSheetBinding.buttonUpload3wifi.setOnClickListener {
            bottomSheetDialog.dismiss()
            uploadAllTo3WiFi()
        }

        bottomSheetBinding.buttonImportWpaSec.setOnClickListener {
            bottomSheetDialog.dismiss()
            showWpaSecImportDialog()
        }

        bottomSheetBinding.buttonClearDatabase.setOnClickListener {
            bottomSheetDialog.dismiss()
            showClearDatabaseDialog()
        }

        bottomSheetDialog.show()
    }

    private fun showWpaSecImportDialog() {
        val input =
            TextInputEditText(requireContext()).apply {
                hint = getString(R.string.wpa_sec_api_key_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                filters = arrayOf(android.text.InputFilter.LengthFilter(32))
                isSingleLine = true
            }

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(input)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wpa_sec_api_key)
            .setView(container)
            .setPositiveButton(R.string.import_wpa_sec) { _, _ ->
                val key = input.text?.toString()?.trim() ?: ""
                if (key.matches(Regex("^[a-fA-F0-9]{32}$"))) {
                    startWpaSecImport(key)
                } else {
                    showSnackbar(
                        getString(
                            R.string.wpa_sec_import_failed,
                            getString(R.string.ia_invalid_api_key_format)
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startWpaSecImport(apiKey: String) {
        showProgressDialog(getString(R.string.wpa_sec_import_progress))
        viewModel.importFromWpaSec(
            apiKey = apiKey,
            onProgress = { loading ->
                if (!loading) hideProgressDialog()
            },
            onResult = { inserted, duplicates ->
                showSnackbar(getString(R.string.wpa_sec_import_result, inserted, duplicates))
                adapter.refresh()
                updateStats()
            },
            onError = { error ->
                showSnackbar(getString(R.string.wpa_sec_import_failed, error))
            }
        )
    }

    private fun showIndexLevelSelectionDialog() {
        val indexLevels = arrayOf(
            getString(R.string.index_level_full_option),
            getString(R.string.index_level_basic_option)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_index_level)
            .setItems(indexLevels) { _, which ->
                val level = when (which) {
                    0 -> "FULL"
                    1 -> "BASIC"
                    else -> "BASIC"
                }
                viewModel.enableIndexing(level)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
            }
            .show()
    }

    private fun showImportDialog() {
        val formats = arrayOf(
            getString(R.string.ds_format_json),
            getString(R.string.ds_format_csv),
            getString(R.string.ds_format_txt_router_scan)
        )
        MaterialAlertDialogBuilder(requireContext())
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
        val formats = arrayOf(
            getString(R.string.ds_format_json),
            getString(R.string.ds_format_csv)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_format)
            .setItems(formats) { _, which ->
                when (which) {
                    0 -> startFileCreation(
                        "wifi_database.json",
                        "application/json",
                        REQUEST_EXPORT_JSON
                    )

                    1 -> startFileCreation("wifi_database.csv", "text/csv", REQUEST_EXPORT_CSV)
                }
            }
            .show()
    }

    private fun showClearDatabaseDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.warning)
            .setMessage(R.string.clear_database_warning)
            .setPositiveButton(R.string.yes) { _, _ ->
                showBackupBeforeClearDialog()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun showBackupBeforeClearDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup)
            .setMessage(R.string.backup_before_clear)
            .setPositiveButton(R.string.yes) { _, _ ->
                isBackupBeforeClear = true
                selectBackupLocation()
            }
            .setNegativeButton(R.string.no) { _, _ ->
                clearLocalDatabase()
            }
            .show()
    }

    private fun clearLocalDatabase() {
        viewModel.clearDatabase()
        adapter.refresh()
        updateStats()
        showSnackbar(getString(R.string.database_cleared))
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
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("text/csv", "text/comma-separated-values", "application/csv")
                )
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
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/x-sqlite3",
                    "application/octet-stream",
                    "application/vnd.sqlite3"
                )
            )
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
                    showImportTypeDialog { importType ->
                        showProgressDialog(getString(R.string.importing_data))
                        viewModel.importFromJson(uri, importType) {
                            hideProgressDialog()
                            adapter.refresh()
                            updateStats()
                            showSnackbar(getString(R.string.import_successful))
                        }
                    }
                }

                REQUEST_IMPORT_CSV -> {
                    showImportTypeDialog { importType ->
                        showProgressDialog(getString(R.string.importing_data))
                        viewModel.importFromCsv(uri, importType) {
                            hideProgressDialog()
                            adapter.refresh()
                            updateStats()
                            showSnackbar(getString(R.string.import_successful))
                        }
                    }
                }

                REQUEST_IMPORT_ROUTERSCAN -> {
                    showRouterScanImportTypeDialog(uri)
                }

                REQUEST_BACKUP_DB -> {
                    viewModel.exportDatabase(uri)
                    if (isBackupBeforeClear) {
                        clearLocalDatabase()
                        isBackupBeforeClear = false
                        showSnackbar(getString(R.string.database_backed_up_and_cleared))
                    } else {
                        showSnackbar(getString(R.string.database_backed_up))
                    }
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

    private fun showImportTypeDialog(onTypeSelected: (String) -> Unit) {
        val options = arrayOf(
            getString(R.string.replace_database),
            getString(R.string.append_no_duplicates),
            getString(R.string.append_check_duplicates)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_import_type)
            .setItems(options) { _, which ->
                val importType = when (which) {
                    0 -> "replace"
                    1 -> "append_no_duplicates"
                    2 -> "append_check_duplicates"
                    else -> "append_no_duplicates"
                }
                onTypeSelected(importType)
            }
            .show()
    }

    private fun showRouterScanImportTypeDialog(uri: Uri) {
        val options = arrayOf(
            getString(R.string.replace_database),
            getString(R.string.append_no_duplicates),
            getString(R.string.append_check_duplicates)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_import_type)
            .setItems(options) { _, which ->
                val importType = when (which) {
                    0 -> "replace"
                    1 -> "append_no_duplicates"
                    2 -> "append_check_duplicates"
                    else -> "append_no_duplicates"
                }

                showRouterScanImportProgress(uri, importType)
            }
            .show()
    }

    private fun showRouterScanImportProgress(uri: Uri, importType: String) {
        if (!isAdded) return

        val context = context ?: return

        val progressDialog = MaterialAlertDialogBuilder(context)
            .setView(R.layout.dialog_import_progress)
            .setCancelable(false)
            .show()

        val progressText = progressDialog.findViewById<TextView>(R.id.textViewImportProgress)
        val progressBar = progressDialog.findViewById<ProgressBar>(R.id.progressBarImport)

        progressText?.text = getString(R.string.importing_data)
        progressBar?.progress = 0

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stats = viewModel.importFromRouterScanWithProgress(
                    uri,
                    importType
                ) { message, progress ->
                    launch(Dispatchers.Main) {
                        if (isAdded && progressDialog.isShowing && _binding != null) {
                            progressText?.text = message
                            progressBar?.progress = progress
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null) {
                        progressDialog.dismiss()
                        adapter.refresh()
                        updateStats()

                        val message = when (importType) {
                            "append_check_duplicates" -> getString(
                                R.string.import_stats,
                                stats.totalProcessed, stats.inserted, stats.duplicates
                            )

                            "replace" -> getString(
                                R.string.database_replaced_imported,
                                stats.inserted
                            )

                            else -> getString(R.string.import_completed_added, stats.inserted)
                        }

                        showSnackbar(message)
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null) {
                        progressDialog.dismiss()
                        showSnackbar(getString(R.string.import_error, e.message))
                    }
                }
            }
        }
    }

    private fun showProgressDialog(message: String) {
        binding.progressBarDatabaseCheck.startAnimation()
        hideProgressDialog()

        progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(message)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }

    private fun hideProgressDialog() {
        binding.progressBarDatabaseCheck.stopAnimation()
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun observeViewModel() {
        viewModel.databaseStats.observe(viewLifecycleOwner) { stats ->
            binding.textViewDbStats.text = stats
        }

        viewModel.indexStatus.observe(viewLifecycleOwner) { status ->
            binding.textViewIndexStatus.text = getString(R.string.indexing_status, status)
        }

        viewModel.wpaSecToast.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                viewModel.clearWpaSecToast()
            }
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

    private fun uploadAllTo3WiFi() {
        viewLifecycleOwner.lifecycleScope.launch {
            val allRecords = withContext(Dispatchers.IO) {
                LocalAppDbHelper(requireContext()).use { dbHelper ->
                    dbHelper.getAllRecords()
                }
            }
            val uploadable =
                allRecords.filter { !it.wifiPassword.isNullOrBlank() || !it.wpsCode.isNullOrBlank() }
            if (uploadable.isEmpty()) {
                showSnackbar(getString(R.string.router_scan_upload_empty))
                return@launch
            }
            doUploadRecords(uploadable)
        }
    }

    private fun uploadRecordTo3WiFi(records: List<WifiNetwork>) {
        viewLifecycleOwner.lifecycleScope.launch {
            doUploadRecords(records)
        }
    }

    private suspend fun doUploadRecords(records: List<WifiNetwork>) {
        dbSetupViewModel.loadDbList()
        delay(300)
        val servers =
            dbSetupViewModel.dbList.value?.filter { it.dbType == DbType.WIFI_API } ?: emptyList()
        if (servers.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_3wifi_servers, Toast.LENGTH_SHORT).show()
            return
        }
        ThreeWiFiUploader.showServerPicker(requireContext(), servers) { server ->
            val rows = records.map { r ->
                ThreeWiFiCsvRow(
                    bssid = r.macAddress,
                    essid = r.wifiName,
                    key = r.wifiPassword ?: "",
                    wps = r.wpsCode ?: "",
                    title = r.adminPanel ?: "",
                )
            }
            val csv = ThreeWiFiUploader.convertToCsv(rows)
            viewLifecycleOwner.lifecycleScope.launch {
                val result = ThreeWiFiUploader.uploadCsv(requireContext(), server, csv)
                val msg = if (result.success) getString(R.string.upload_success_text)
                else "${getString(R.string.upload_failed_text)}: ${result.message}"
                Toast.makeText(
                    requireContext(), msg,
                    if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
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