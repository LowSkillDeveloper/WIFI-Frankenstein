package com.lsd.wififrankenstein.ui.dbsetup

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentDbSetupBinding
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbViewModel
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.ui.welcome.DbSourceAdapter
import com.lsd.wififrankenstein.ui.welcome.WelcomeDatabaseAdapter
import com.lsd.wififrankenstein.util.Log
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class DbSetupFragment : Fragment() {

    private var _binding: FragmentDbSetupBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<DbSetupViewModel>()
    private val localAppDbViewModel: LocalAppDbViewModel by viewModels()
    private lateinit var dbListAdapter: DbListAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var isBackupBeforeClear = false

    private var lastSelectedDbType: String? = null
    private val PREFS_NAME = "db_setup_ui_prefs"
    private val KEY_LAST_DB_TYPE = "last_db_type"

    private lateinit var dbSourceAdapter: DbSourceAdapter
    private lateinit var recommendedDatabasesAdapter: WelcomeDatabaseAdapter
    private var currentViewMode = ViewMode.SOURCES
    private var isShowingDatabases = false

    enum class ViewMode {
        SOURCES, DATABASES
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                selectFile()
            } else {
                Snackbar.make(
                    binding.root,
                    getString(R.string.permission_denied_select_file),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDbSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupDbTypeSpinner()
        setupRecyclerView()
        setupButtons()
        observeViewModel()
        setupLocalDbCard()
        updateLocalDbStats()
        setupAdvancedOptions()
        setupRecommendedDatabasesToggle()
        setupRecommendedDatabases()
        checkRecommendedDatabases()

        viewModel.showColumnMappingEvent.observe(viewLifecycleOwner) { event ->
            showCustomDbSetupDialog(event.dbType, event.type, event.path, event.directPath)
        }

        viewModel.dbList.observe(viewLifecycleOwner) { dbList ->
            dbListAdapter.submitList(dbList)
            Log.d("DbSetupFragment", "Updating UI with ${dbList.size} items")
        }
    }

    private fun setupAdvancedOptions() {
        binding.buttonExpandAdvancedOptions.setOnClickListener {
            toggleAdvancedOptions()
        }
    }

    private fun toggleAdvancedOptions() {
        val isExpanded = binding.layoutAdvancedOptions.isVisible
        binding.layoutAdvancedOptions.visibility = if (isExpanded) View.GONE else View.VISIBLE
        val iconRes = if (isExpanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less
        binding.buttonExpandAdvancedOptions.setIconResource(iconRes)
    }

    private fun updateLocalDbStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val totalRecords = viewModel.getTotalRecordsCount()
            val dbSize = viewModel.getDbSize()
            binding.textViewDbStats.text = getString(R.string.db_stats, totalRecords, dbSize)
        }
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
                localAppDbViewModel.toggleIndexing(true, level)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                binding.switchEnableIndexing.isChecked = false
            }
            .setOnCancelListener {
                binding.switchEnableIndexing.isChecked = false
            }
            .show()
    }

    private fun setupRecommendedDatabases() {
        dbSourceAdapter = DbSourceAdapter { source ->
            loadDatabasesFromSource(source)
        }

        recommendedDatabasesAdapter = WelcomeDatabaseAdapter(
            onAddDatabase = { database ->
                viewLifecycleOwner.lifecycleScope.launch {
                    downloadAndAddDatabase(database)
                }
            },
            isSelectedList = false
        )

        binding.recyclerViewRecommended.layoutManager = LinearLayoutManager(requireContext())

        binding.buttonBackToSources.setOnClickListener {
            goBackToSources()
        }
    }

    private fun checkRecommendedDatabases() {
        if (binding.layoutRecommendedDatabases.isVisible) {
            binding.progressBarRecommended.visibility = View.VISIBLE
            binding.textViewRecommendedDescription.text = getString(R.string.loading_recommendations)
            binding.recyclerViewRecommended.visibility = View.GONE
        }

        val recommendedSourcesUrl = "https://raw.githubusercontent.com/LowSkillDeveloper/WIFI-Frankenstein/refs/heads/service/recommended-databases.json"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = withTimeoutOrNull(2500) {
                    viewModel.fetchSources(recommendedSourcesUrl)
                    true
                } == true

                if (success) {
                    viewModel.sources.observe(viewLifecycleOwner) { sources ->
                        binding.progressBarRecommended.visibility = View.GONE

                        if (sources.isNotEmpty()) {
                            showSources(sources)
                        } else {
                            showNoRecommendations()
                        }
                    }
                } else {
                    showNoRecommendations()
                }
            } catch (e: Exception) {
                showNoRecommendations()
            }
        }
    }

    private fun setupRecommendedDatabasesToggle() {
        binding.buttonExpandRecommendedDatabases.setOnClickListener {
            toggleRecommendedDatabases()
        }
    }

    private fun toggleRecommendedDatabases() {
        val isExpanded = binding.layoutRecommendedDatabases.isVisible
        binding.layoutRecommendedDatabases.visibility = if (isExpanded) View.GONE else View.VISIBLE
        val iconRes = if (isExpanded) R.drawable.ic_expand_more else R.drawable.ic_expand_less
        val textRes = if (isExpanded) R.string.show_recommended_databases else R.string.hide_recommended_databases
        binding.buttonExpandRecommendedDatabases.setIconResource(iconRes)
        binding.buttonExpandRecommendedDatabases.text = getString(textRes)

        if (!isExpanded && !isShowingDatabases) {
            checkRecommendedDatabases()
        }
    }

    private fun showSources(sources: List<DbSource>) {
        currentViewMode = ViewMode.SOURCES
        isShowingDatabases = false

        binding.textViewRecommendedTitle.text = getString(R.string.recommended_databases)
        binding.textViewRecommendedDescription.text = getString(R.string.select_database_source)
        binding.recyclerViewRecommended.adapter = dbSourceAdapter
        if (binding.layoutRecommendedDatabases.isVisible) {
            binding.recyclerViewRecommended.visibility = View.VISIBLE
        }
        binding.buttonBackToSources.visibility = View.GONE
        dbSourceAdapter.submitList(sources)
    }

    private fun showNoRecommendations() {
        binding.progressBarRecommended.visibility = View.GONE
        binding.textViewRecommendedTitle.text = getString(R.string.recommended_community_databases)
        binding.textViewRecommendedDescription.text = getString(R.string.no_recommendations_available)
        if (binding.layoutRecommendedDatabases.isVisible) {
            binding.recyclerViewRecommended.visibility = View.GONE
        }
        binding.buttonBackToSources.visibility = View.GONE
    }

    private fun loadDatabasesFromSource(source: DbSource) {
        binding.progressBarRecommended.visibility = View.VISIBLE
        binding.recyclerViewRecommended.visibility = View.GONE

        viewModel.setCurrentSource(source)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.fetchSmartLinkDatabases(source.smartlinkUrl)

                viewModel.smartLinkDatabases.observe(viewLifecycleOwner) { databases ->
                    binding.progressBarRecommended.visibility = View.GONE

                    if (databases != null && databases.isNotEmpty()) {
                        showDatabasesFromSource(source, databases)
                    } else {
                        binding.textViewRecommendedDescription.text = getString(R.string.no_recommendations_available)
                    }
                }
            } catch (e: Exception) {
                binding.progressBarRecommended.visibility = View.GONE
                binding.textViewRecommendedDescription.text = getString(R.string.no_recommendations_available)
            }
        }
    }

    private fun showDatabasesFromSource(source: DbSource, databases: List<SmartLinkDbInfo>) {
        currentViewMode = ViewMode.DATABASES
        isShowingDatabases = true

        binding.textViewRecommendedTitle.text = getString(R.string.recommended_databases)
        binding.textViewRecommendedDescription.text = getString(R.string.databases_from_source, source.name)
        binding.recyclerViewRecommended.adapter = recommendedDatabasesAdapter
        if (binding.layoutRecommendedDatabases.isVisible) {
            binding.recyclerViewRecommended.visibility = View.VISIBLE
        }
        binding.buttonBackToSources.visibility = View.VISIBLE

        val dbItems = databases.map {
            createDbItemFromSmartLinkInfo(it).copy(
                updateUrl = source.smartlinkUrl
            )
        }
        recommendedDatabasesAdapter.submitList(dbItems)
    }

    private fun goBackToSources() {
        if (isShowingDatabases) {
            checkRecommendedDatabases()
        }
    }

    private fun downloadAndAddDatabase(database: DbItem) {
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(R.layout.dialog_download_progress)
            .setCancelable(false)
            .create()

        progressDialog.show()

        val progressText = progressDialog.findViewById<TextView>(R.id.textViewProgress)
        val progressBar = progressDialog.findViewById<ProgressBar>(R.id.progressBarDownload)
        val cancelButton = progressDialog.findViewById<Button>(R.id.buttonCancel)

        var isCancelled = false
        val downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val currentSource = viewModel.sources.value?.find { source ->
                    viewModel.smartLinkDatabases.value?.any { it.id == database.idJson } == true
                }

                val sourceUrl = currentSource?.smartlinkUrl ?: database.updateUrl

                if (sourceUrl.isNullOrBlank()) {
                    showSnackbar(getString(R.string.error_downloading_database, "Source URL not found"))
                    return@launch
                }

                viewModel.fetchSmartLinkDatabases(sourceUrl)
                delay(500)

                val dbInfo = viewModel.smartLinkDatabases.value?.find {
                    it.id == database.idJson
                } ?: return@launch

                progressText?.text = getString(R.string.downloading_database_progress, 1, 1, dbInfo.name)

                val downloadedDb = viewModel.downloadSmartLinkDatabase(dbInfo) { progress, downloaded, total ->
                    if (!isCancelled) {
                        progressBar?.progress = progress
                        val downloadedMB = downloaded / (1024 * 1024)
                        val totalMB = total?.let { it / (1024 * 1024) }

                        val progressMessage = when (progress) {
                            -1 -> getString(R.string.extracting_archive)
                            -2 -> getString(R.string.downloading_archive_part, downloaded.toInt(), total?.toInt() ?: 0)
                            -3 -> getString(R.string.merging_archive_parts)
                            else -> {
                                if (total != null) {
                                    getString(R.string.downloading_database_progress_size,
                                        1, 1, dbInfo.name, downloadedMB, totalMB)
                                } else {
                                    getString(R.string.downloading_database_progress_no_size,
                                        1, 1, dbInfo.name, downloadedMB)
                                }
                            }
                        }
                        progressText?.text = progressMessage
                    }
                }

                downloadedDb?.let { item ->
                    progressDialog.dismiss()

                    if (item.dbType == DbType.SQLITE_FILE_CUSTOM || item.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM) {
                        viewModel.initializeSQLiteCustomHelper(item.path.toUri(), item.directPath)

                        if (dbInfo.type == "custom-auto-mapping") {
                            if (item.tableName != null && item.columnMap != null && item.columnMap.isNotEmpty()) {
                                val finalDbItem = item.copy(
                                    tableName = item.tableName,
                                    columnMap = item.columnMap
                                )
                                viewModel.addDb(finalDbItem)
                                showSnackbar(getString(R.string.auto_mapping_applied))
                            } else {
                                showCustomDbSetupDialog(
                                    dbType = item.dbType,
                                    type = item.type,
                                    path = item.path,
                                    directPath = item.directPath
                                )
                            }
                        } else {
                            showCustomDbSetupDialog(
                                dbType = item.dbType,
                                type = item.type,
                                path = item.path,
                                directPath = item.directPath
                            )
                        }
                    } else {
                        viewModel.addDb(item)
                        showSnackbar(getString(R.string.db_added_successfully))
                    }
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                showSnackbar(getString(R.string.error_downloading_database, e.message ?: "Unknown error"))
            }
        }

        cancelButton?.setOnClickListener {
            isCancelled = true
            downloadJob.cancel()
            progressDialog.dismiss()
        }
    }

    private fun createDbItemFromSmartLinkInfo(smartLinkInfo: SmartLinkDbInfo): DbItem {
        val dbType = if (smartLinkInfo.type == "3wifi")
            DbType.SMARTLINK_SQLITE_FILE_3WIFI
        else
            DbType.SMARTLINK_SQLITE_FILE_CUSTOM

        return DbItem(
            id = UUID.randomUUID().toString(),
            path = smartLinkInfo.downloadUrls.firstOrNull() ?: "",
            directPath = null,
            type = smartLinkInfo.name,
            dbType = dbType,
            originalSizeInMB = 0f,
            cachedSizeInMB = 0f,
            idJson = smartLinkInfo.id,
            version = smartLinkInfo.version,
            updateUrl = viewModel.sources.value?.find {
                viewModel.getCurrentSource()?.id == it.id
            }?.smartlinkUrl ?: "",
            smartlinkType = smartLinkInfo.type,
            tableName = null,
            columnMap = smartLinkInfo.columnMapping
        )
    }

    private fun setupLocalDbCard() {
        binding.switchEnableIndexing.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (localAppDbViewModel.isIndexingConfigured()) {
                    localAppDbViewModel.toggleIndexing(true)
                } else {
                    showIndexLevelSelectionDialog()
                }
            } else {
                localAppDbViewModel.toggleIndexing(false)
            }
        }

        binding.buttonClearLocalDb.setOnClickListener {
            showClearDatabaseWarning()
        }

        binding.buttonManageLocalDb.setOnClickListener {
            findNavController().navigate(R.id.action_dbSetupFragment_to_localDbManagementFragment)
        }

        binding.buttonSearchLocalDb.setOnClickListener {
            findNavController().navigate(R.id.nav_local_db_viewer)
        }

        binding.buttonOptimizeDb.setOnClickListener {
            localAppDbViewModel.optimizeDatabase()
            showSnackbar(getString(R.string.database_optimized))
        }

        binding.buttonRemoveDuplicates.setOnClickListener {
            localAppDbViewModel.removeDuplicates()
            showSnackbar(getString(R.string.duplicates_removed))
        }

        binding.buttonBackupDb.setOnClickListener {
            selectBackupLocation()
        }

        binding.buttonExportDb.setOnClickListener {
            showExportDialog()
        }

        binding.buttonImportDb.setOnClickListener {
            showImportDialog()
        }

        binding.buttonRestoreDb.setOnClickListener {
            selectRestoreFile()
        }

        localAppDbViewModel.isIndexingEnabled.observe(viewLifecycleOwner) { isEnabled ->
            binding.switchEnableIndexing.isChecked = isEnabled
        }
    }


    private fun showClearDatabaseWarning() {
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

    private fun saveLastDbType(dbType: String) {
        lastSelectedDbType = dbType
        requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putString(KEY_LAST_DB_TYPE, dbType)
            }
        Log.d("DbSetupFragment", "Saved last DB type: $dbType")
    }

    private fun loadLastDbType(): String? {
        val savedType = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DB_TYPE, null)
        lastSelectedDbType = savedType
        Log.d("DbSetupFragment", "Loaded last DB type: $savedType")
        return savedType
    }

    private fun clearLocalDatabase() {
        viewLifecycleOwner.lifecycleScope.launch {
            localAppDbViewModel.clearDatabase()
            updateLocalDbStats()
            showSnackbar(getString(R.string.database_cleared))
        }
    }

    private fun showExportDialog() {
        val formats = arrayOf("JSON", "CSV")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_format)
            .setItems(formats) { _, which ->
                when (which) {
                    0 -> startFileCreation("wifi_database.json", "application/json", REQUEST_EXPORT_JSON)
                    1 -> startFileCreation("wifi_database.csv", "text/csv", REQUEST_EXPORT_CSV)
                }
            }
            .show()
    }

    private fun showImportDialog() {
        val formats = arrayOf("JSON", "CSV", "TXT (RouterScan)")
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

    private fun exportToJson(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val records = localAppDbViewModel.getAllRecords()
                val json = Json.encodeToString(records)
                context?.contentResolver?.openOutputStream(uri)?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer ->
                        writer.write(json)
                    }
                }
                withContext(Dispatchers.Main) {
                    showSnackbar(getString(R.string.export_successful))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showSnackbar(getString(R.string.export_failed, e.message))
                }
            }
        }
    }

    private fun exportToCsv(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val records = localAppDbViewModel.getAllRecords()
                context?.contentResolver?.openOutputStream(uri)?.use { outputStream ->
                    CSVWriter(OutputStreamWriter(outputStream)).use { writer ->
                        writer.writeNext(arrayOf("ID", "WiFi Name", "MAC Address", "Password", "WPS PIN", "Admin Panel", "Latitude", "Longitude"))
                        records.forEach { record ->
                            writer.writeNext(arrayOf(
                                record.id.toString(),
                                record.wifiName,
                                record.macAddress,
                                record.wifiPassword ?: "",
                                record.wpsCode ?: "",
                                record.adminPanel ?: "",
                                record.latitude?.toString() ?: "",
                                record.longitude?.toString() ?: ""
                            ))
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    showSnackbar(getString(R.string.export_successful))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showSnackbar(getString(R.string.export_failed, e.message))
                }
            }
        }
    }

    private fun importFromJson(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                context?.contentResolver?.openInputStream(uri)?.use { inputStream ->
                    InputStreamReader(inputStream).use { reader ->
                        val json = reader.readText()
                        val records = Json.decodeFromString<List<WifiNetwork>>(json)
                        localAppDbViewModel.importRecords(records)
                        withContext(Dispatchers.Main) {
                            showSnackbar(getString(R.string.import_successful))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showSnackbar(getString(R.string.import_failed, e.message))
                }
            }
        }
    }

    private fun importFromCsv(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                context?.contentResolver?.openInputStream(uri)?.use { inputStream ->
                    CSVReader(InputStreamReader(inputStream)).use { reader ->
                        val records = reader.readAll().drop(1).map { row ->
                            WifiNetwork(
                                id = row[0].toLongOrNull() ?: 0,
                                wifiName = row[1],
                                macAddress = row[2],
                                wifiPassword = row[3].takeIf { it.isNotEmpty() },
                                wpsCode = row[4].takeIf { it.isNotEmpty() },
                                adminPanel = row[5].takeIf { it.isNotEmpty() },
                                latitude = row[6].toDoubleOrNull(),
                                longitude = row[7].toDoubleOrNull()
                            )
                        }
                        localAppDbViewModel.importRecords(records)
                        withContext(Dispatchers.Main) {
                            showSnackbar(getString(R.string.import_successful))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showSnackbar(getString(R.string.import_failed, e.message))
                }
            }
        }
    }

    private fun importFromRouterScan(uri: Uri) {
        if (!isAdded) return

        showRouterScanImportTypeDialog(uri)
    }

    private fun showRouterScanImportTypeDialog(uri: Uri) {
        if (!isAdded) return

        val options = arrayOf(
            getString(R.string.replace_database),
            getString(R.string.append_no_duplicates),
            getString(R.string.append_check_duplicates)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_import_type)
            .setItems(options) { _, which ->
                if (!isAdded) return@setItems

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
                val networksToAdd = mutableListOf<WifiNetwork>()
                var processedLines = 0
                var totalLines = 0

                withContext(Dispatchers.Main) {
                    progressText?.text = "Анализ файла..."
                    progressBar?.progress = 2
                }

                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.lineSequence().forEach { _ -> totalLines++ }
                }

                withContext(Dispatchers.Main) {
                    progressText?.text = "Парсинг $totalLines строк..."
                    progressBar?.progress = 5
                }

                val parseJobs = mutableListOf<Deferred<List<WifiNetwork>>>()
                val chunkSize = 10000

                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val allLines = reader.readLines()

                    allLines.chunked(chunkSize).forEachIndexed { chunkIndex, linesChunk ->
                        val job = async {
                            val chunkNetworks = mutableListOf<WifiNetwork>()
                            linesChunk.forEach { line ->
                                if (!isActive) return@forEach
                                parseRouterScanLine(line, importType)?.let { network ->
                                    chunkNetworks.add(network)
                                }
                            }

                            withContext(Dispatchers.Main) {
                                if (isAdded && progressDialog.isShowing) {
                                    processedLines += linesChunk.size
                                    val progress = 5 + (processedLines * 10) / totalLines
                                    progressBar?.progress = progress
                                    progressText?.text = "Парсинг: $processedLines/$totalLines"
                                }
                            }

                            chunkNetworks
                        }
                        parseJobs.add(job)
                    }
                }

                parseJobs.awaitAll().forEach { chunk ->
                    networksToAdd.addAll(chunk)
                }

                withContext(Dispatchers.Main) {
                    progressBar?.progress = 15
                    progressText?.text = "Найдено ${networksToAdd.size} записей для импорта"
                }

                val stats = localAppDbViewModel.importRecordsWithStats(
                    networksToAdd,
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
                        updateLocalDbStats()

                        val message = when (importType) {
                            "append_check_duplicates" -> getString(R.string.import_stats,
                                stats.totalProcessed, stats.inserted, stats.duplicates)
                            "replace" -> "База заменена\nИмпортировано: ${stats.inserted} записей"
                            else -> "Импорт завершён\nДобавлено: ${stats.inserted} записей"
                        }

                        showSnackbar(message)
                    }
                }

            } catch (e: Exception) {
                Log.e("RouterScanImport", "Import error", e)
                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null) {
                        progressDialog.dismiss()
                        showSnackbar("Ошибка импорта: ${e.message}")
                    }
                }
            }
        }
    }

    private fun parseRouterScanLine(line: String, importType: String): WifiNetwork? {
        if (line.trim().isEmpty() || line.startsWith("#")) return null

        val parts = line.split("\t")

        if (parts.size >= 9) {
            try {
                val bssid = if (parts.size > 8) parts[8].trim() else ""
                val essid = if (parts.size > 9) parts[9].trim() else ""
                val wifiKey = if (parts.size > 11) parts[11].trim() else ""
                val wpsPin = if (parts.size > 12) parts[12].trim() else ""
                val adminCredentials = if (parts.size > 4) parts[4].trim() else ""

                var latitude: Double? = null
                var longitude: Double? = null

                if (parts.size >= 14) {
                    try {
                        for (i in (parts.size - 5) until (parts.size - 1)) {
                            if (i >= 0 && i + 1 < parts.size) {
                                val latStr = parts[i].trim()
                                val lonStr = parts[i + 1].trim()

                                if (latStr.matches(Regex("^\\d{1,2}\\.\\d+$")) &&
                                    lonStr.matches(Regex("^\\d{1,3}\\.\\d+$"))) {
                                    val lat = latStr.toDoubleOrNull()
                                    val lon = lonStr.toDoubleOrNull()

                                    if (lat != null && lon != null &&
                                        lat >= -90.0 && lat <= 90.0 &&
                                        lon >= -180.0 && lon <= 180.0 &&
                                        lat != 0.0 && lon != 0.0) {
                                        latitude = lat
                                        longitude = lon
                                        break
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                    }
                }

                if (essid.isNotEmpty() || bssid.isNotEmpty()) {
                    val cleanBssid = bssid.uppercase().replace("-", ":").trim()
                    val cleanEssid = essid.trim()
                    val cleanWifiKey = wifiKey.takeIf { it.isNotEmpty() && it != "0" && it != "-" && it.length > 1 }
                    val cleanWpsPin = wpsPin.takeIf { it.isNotEmpty() && it != "0" && it != "-" && it.length >= 8 }
                    val cleanAdminPanel = adminCredentials.takeIf {
                        it.isNotEmpty() && it != ":" && it != "-" && !it.contains("0.0.0.0") && it.contains(":")
                    }

                    if (cleanBssid.isNotEmpty()) {
                        if (!cleanBssid.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))) {
                            return null
                        }
                    }

                    if (cleanEssid.isNotEmpty() || cleanBssid.isNotEmpty()) {
                        return WifiNetwork(
                            id = 0,
                            wifiName = cleanEssid,
                            macAddress = cleanBssid,
                            wifiPassword = cleanWifiKey,
                            wpsCode = cleanWpsPin,
                            adminPanel = cleanAdminPanel,
                            latitude = latitude,
                            longitude = longitude
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d("RouterScanImport", "Error parsing line: $line", e)
            }
        }

        return null
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
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                REQUEST_SELECT_FILE -> {
                    data?.data?.let { uri ->
                        handleSelectedFile(uri)
                    }
                }
                REQUEST_BACKUP_DB -> {
                    data?.data?.let { uri ->
                        exportDatabase(uri)
                        if (isBackupBeforeClear) {
                            clearLocalDatabase()
                            isBackupBeforeClear = false
                            showSnackbar(getString(R.string.database_backed_up_and_cleared))
                        } else {
                            showSnackbar(getString(R.string.database_backed_up))
                        }
                    }
                }
                REQUEST_RESTORE_DB -> {
                    data?.data?.let { uri ->
                        localAppDbViewModel.restoreDatabaseFromUri(uri)
                        showSnackbar(getString(R.string.database_restored))
                        reloadFragment()
                    }
                }
                REQUEST_EXPORT_JSON -> {
                    data?.data?.let { uri ->
                        exportToJson(uri)
                    }
                }
                REQUEST_EXPORT_CSV -> {
                    data?.data?.let { uri ->
                        exportToCsv(uri)
                    }
                }
                REQUEST_IMPORT_JSON -> {
                    data?.data?.let { uri ->
                        importFromJson(uri)
                        reloadFragment()
                    }
                }
                REQUEST_IMPORT_CSV -> {
                    data?.data?.let { uri ->
                        importFromCsv(uri)
                        reloadFragment()
                    }
                }
                REQUEST_IMPORT_ROUTERSCAN -> {
                    data?.data?.let { uri ->
                        importFromRouterScan(uri)
                    }
                }
            }
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        try {
            context?.contentResolver?.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            binding.buttonSelectFile.tag = uri.toString()

            val directPath = getDirectPathFromUri(uri)
            viewModel.setSelectedFilePaths(uri.toString(), directPath)

            val size = getFileSizeInMB(uri)
            viewModel.setSelectedFileSize(size)
            val fileName = getDisplayNameFromUri(uri)
            binding.textViewSelectedFile.text = getString(R.string.selected_file, fileName)
            binding.textViewSelectedFile.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e("DbSetupFragment", "Error processing selected file", e)
            Toast.makeText(
                context,
                "Error processing selected file: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun reloadFragment() {
        findNavController().run {
            popBackStack()
            navigate(R.id.dbSetupFragment)
        }
    }

    private fun exportDatabase(uri: Uri) {
        try {
            val dbFile = File(context?.getDatabasePath("local_wifi_database.db")?.path ?: "")
            context?.contentResolver?.openOutputStream(uri)?.use { outputStream ->
                dbFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Toast.makeText(context, "Database exported successfully", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to export database: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()

        viewModel.updateAllDbIndexStatuses()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loadDbList()
            updateDbSizes()
            viewModel.checkAndUpdateDatabases()
            viewModel.updateAllDbIndexStatuses()
            delay(200)
            dbListAdapter.notifyDataSetChanged()
        }
    }

    private fun setupDbTypeSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.db_types,
            android.R.layout.simple_dropdown_item_1line
        )
        (binding.spinnerDbType.editText as? AutoCompleteTextView)?.setAdapter(adapter)

        val lastType = loadLastDbType()
        if (lastType != null) {
            (binding.spinnerDbType.editText as? AutoCompleteTextView)?.setText(lastType, false)

            when (lastType) {
                getString(R.string.db_type_sqlite_3wifi),
                getString(R.string.db_type_sqlite_custom) -> {
                    binding.textInputLink.visibility = View.GONE
                    binding.buttonSelectFile.visibility = View.VISIBLE
                    binding.spinnerAuthMethod.visibility = View.GONE
                    hideAllAuthFields()
                    checkStoragePermission()
                }
                getString(R.string.db_type_3wifi) -> {
                    binding.textInputLink.visibility = View.VISIBLE
                    binding.buttonSelectFile.visibility = View.GONE
                    binding.spinnerAuthMethod.visibility = View.VISIBLE
                    setupAuthMethodSpinner()
                }
                getString(R.string.db_type_smartlink) -> {
                    binding.textInputLink.visibility = View.VISIBLE
                    binding.buttonSelectFile.visibility = View.GONE
                    binding.spinnerAuthMethod.visibility = View.GONE
                    hideAllAuthFields()
                    binding.buttonAdd.text = getString(R.string.fetch_smartlink_databases)
                }
            }
            binding.buttonAdd.visibility = View.VISIBLE
        }

        (binding.spinnerDbType.editText as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            val selectedType = adapter.getItem(position)?.toString()
            if (selectedType != null) {
                saveLastDbType(selectedType)
            }

            when (position) {
                0, 1 -> { // SQLite file (Custom) or SQLite file (3WiFi type)
                    binding.textInputLink.visibility = View.GONE
                    binding.buttonSelectFile.visibility = View.VISIBLE
                    binding.spinnerAuthMethod.visibility = View.GONE
                    hideAllAuthFields()
                    checkStoragePermission()
                }
                2 -> { // 3WiFi API
                    binding.textInputLink.visibility = View.VISIBLE
                    binding.buttonSelectFile.visibility = View.GONE
                    binding.spinnerAuthMethod.visibility = View.VISIBLE
                    setupAuthMethodSpinner()
                }
                3 -> { // SmartLinkDB
                    binding.textInputLink.visibility = View.VISIBLE
                    binding.buttonSelectFile.visibility = View.GONE
                    binding.spinnerAuthMethod.visibility = View.GONE
                    hideAllAuthFields()
                    binding.buttonAdd.text = getString(R.string.fetch_smartlink_databases)
                }
            }
            binding.buttonAdd.visibility = View.VISIBLE
        }
    }

    private fun hideAllAuthFields() {
        binding.textInputApiReadKey.visibility = View.GONE
        binding.textInputApiWriteKey.visibility = View.GONE
        binding.textInputLogin.visibility = View.GONE
        binding.textInputPassword.visibility = View.GONE
        binding.textViewUserInfo.visibility = View.GONE
    }

    private fun setupRecyclerView() {
        binding.recyclerViewDbs.apply {
            layoutManager = LinearLayoutManager(context)
            dbListAdapter = DbListAdapter(
                onItemMoved = viewModel::updateDbOrder,
                onStartDrag = { viewHolder -> itemTouchHelper.startDrag(viewHolder) },
                onItemRemoved = { position ->
                    viewModel.removeDb(position)
                    Toast.makeText(
                        context,
                        context?.getString(R.string.database_removed),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onManageIndexes = { dbItem ->
                    handleIndexManagement(dbItem)
                }
            )
            adapter = dbListAdapter
        }


        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                viewModel.updateDbOrder(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })

        itemTouchHelper.attachToRecyclerView(binding.recyclerViewDbs)
    }

    private fun setupAuthMethodSpinner() {
        val authMethods = arrayOf(
            getString(R.string.auth_method_api_keys),
            getString(R.string.auth_method_login_password),
            getString(R.string.auth_method_no_auth)
        )

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authMethods)
        (binding.spinnerAuthMethod.editText as? AutoCompleteTextView)?.setAdapter(adapter)
        (binding.spinnerAuthMethod.editText as? AutoCompleteTextView)?.setText(authMethods[0], false)
        showApiKeysFields()

        (binding.spinnerAuthMethod.editText as? AutoCompleteTextView)?.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> showApiKeysFields()
                1 -> showLoginPasswordFields()
                2 -> showNoAuthFields()
            }
        }
    }

    private fun showApiKeysFields() {
        binding.textInputApiReadKey.visibility = View.VISIBLE
        binding.textInputApiWriteKey.visibility = View.VISIBLE
        binding.textInputLogin.visibility = View.GONE
        binding.textInputPassword.visibility = View.GONE
        binding.textViewUserInfo.visibility = View.GONE
    }

    private fun showLoginPasswordFields() {
        binding.textInputApiReadKey.visibility = View.GONE
        binding.textInputApiWriteKey.visibility = View.GONE
        binding.textInputLogin.visibility = View.VISIBLE
        binding.textInputPassword.visibility = View.VISIBLE
        binding.textViewUserInfo.visibility = View.GONE
    }

    private fun showNoAuthFields() {
        binding.textInputApiReadKey.visibility = View.GONE
        binding.textInputApiWriteKey.visibility = View.GONE
        binding.textInputLogin.visibility = View.GONE
        binding.textInputPassword.visibility = View.GONE
        binding.textViewUserInfo.visibility = View.GONE
    }

    private suspend fun getApiKeysFromLogin(serverUrl: String, login: String, password: String): Triple<String?, String?, Pair<String, Int>?> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/api/apikeys")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val postData = "login=${URLEncoder.encode(login, "UTF-8")}&" +
                        "password=${URLEncoder.encode(password, "UTF-8")}&" +
                        "genread=1"

                connection.outputStream.use { it.write(postData.toByteArray()) }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                if (json.getBoolean("result")) {
                    val profile = json.getJSONObject("profile")
                    val keys = json.getJSONArray("data")

                    var readKey: String? = null
                    var writeKey: String? = null

                    for (i in 0 until keys.length()) {
                        val keyData = keys.getJSONObject(i)
                        val access = keyData.getString("access")
                        when (access) {
                            "read" -> readKey = keyData.getString("key")
                            "write" -> writeKey = keyData.getString("key")
                        }
                    }

                    val userInfo = Pair(
                        profile.getString("nick"),
                        profile.getInt("level")
                    )

                    Triple(readKey, writeKey, userInfo)
                } else {
                    val error = json.getString("error")
                    val userManager = UserManager(requireContext())
                    val errorDesc = userManager.getErrorDesc(error)
                    throw Exception(errorDesc)
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    private fun handleIndexManagement(dbItem: DbItem) {
        if (dbItem.isIndexed) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_indexes)
                .setMessage(R.string.delete_indexes_confirm)
                .setPositiveButton(R.string.yes) { _, _ ->
                    if (viewModel.deleteDbIndexes(dbItem)) {
                        Toast.makeText(context, R.string.indexes_deleted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.operation_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.no, null)
                .show()
        } else {
            showIndexingProgressDialog(dbItem)
        }
    }

    private fun showIndexingProgressDialog(dbItem: DbItem) {
        val indexLevels = arrayOf(
            getString(R.string.index_level_full_option),
            getString(R.string.index_level_basic_option),
            getString(R.string.index_level_none_option)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_index_level)
            .setItems(indexLevels) { _, which ->
                val level = when (which) {
                    0 -> "FULL"
                    1 -> "BASIC"
                    2 -> "NONE"
                    else -> "BASIC"
                }

                requireContext().getSharedPreferences("index_preferences", Context.MODE_PRIVATE)
                    .edit {
                        putString("custom_db_index_level", level)
                    }

                if (level == "NONE") {
                    Toast.makeText(context, R.string.db_added_without_indexes, Toast.LENGTH_SHORT).show()
                    return@setItems
                }

                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.indexing_in_progress)
                    .setView(R.layout.dialog_indexing_progress)
                    .setCancelable(false)
                    .create()

                dialog.show()

                val progressBar = dialog.findViewById<ProgressBar>(R.id.progressBar)

                viewModel.indexingProgress.observe(viewLifecycleOwner) { (id, progress) ->
                    if (id == dbItem.id) {
                        progressBar?.progress = progress
                        if (progress >= 100) {
                            dialog.dismiss()
                            Toast.makeText(context, R.string.indexing_complete, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                lifecycleScope.launch {
                    val result = viewModel.createDbIndexes(dbItem)
                    dialog.dismiss()

                    if (!result) {
                        Toast.makeText(context, R.string.indexing_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun setupButtons() {
        binding.buttonAdd.setOnClickListener {
            addDb()
        }

        binding.buttonSelectFile.setOnClickListener {
            checkAndRequestPermissions()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!viewModel.hasStoragePermissions() && !viewModel.isStoragePermissionMessageShown()) {
                showStoragePermissionDialog()
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED && !viewModel.isStoragePermissionMessageShown()
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_READ_EXTERNAL_STORAGE
                )
            }
        }
    }

    private fun showStoragePermissionDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.warning_storage_access_info)
            .setMessage(R.string.storage_access_info)
            .setPositiveButton(R.string.grant_access) { _, _ ->
                requestStoragePermission()
                viewModel.setStoragePermissionMessageShown()
            }
            .setNegativeButton(R.string.no) { _, _ ->
                showStoragePermissionDeniedDialog()
                viewModel.setStoragePermissionMessageShown()
            }
            .show()
    }

    private fun showStoragePermissionDeniedDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.permission_denied)
            .setMessage(R.string.permission_denied_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = "package:${requireContext().packageName}".toUri()
            }
            startActivity(intent)
        }
    }

    private fun showSmartLinkDatabasesDialog() {
        viewModel.smartLinkDatabases.observe(viewLifecycleOwner, Observer { databases ->
            val items = databases.map {
                "${it.name} (${if (it.type == "3wifi") context?.getString(R.string.type_3wifi) else context?.getString(R.string.type_custom)})"
            }.toTypedArray()
            val checkedItems = BooleanArray(items.size) { false }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.select_databases_to_download)
                .setMultiChoiceItems(items, checkedItems) { _, _, _ -> }
                .setPositiveButton(R.string.download) { _, _ ->
                    val selectedDatabases = databases.filterIndexed { index, _ -> checkedItems[index] }
                    showDownloadProgressDialog(selectedDatabases)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        })
    }

    private fun showDownloadProgressDialog(databases: List<SmartLinkDbInfo>) {
        val dialogJob = Job()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(R.layout.dialog_download_progress)
            .setCancelable(false)
            .create()

        dialog.setOnDismissListener {
            dialogJob.cancel()
        }

        dialog.show()

        val progressText = dialog.findViewById<TextView>(R.id.textViewProgress)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.progressBarDownload)
        val cancelButton = dialog.findViewById<Button>(R.id.buttonCancel)

        var isCancelled = false
        cancelButton?.setOnClickListener {
            isCancelled = true
            dialogJob.cancel()
            dialog.dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch(dialogJob) {
            try {
                databases.forEachIndexed { index, dbInfo ->
                    if (!isActive) return@forEachIndexed

                    progressText?.text = getString(
                        R.string.downloading_database_progress,
                        index + 1, databases.size, dbInfo.name
                    )

                    val dbItem = viewModel.downloadSmartLinkDatabase(dbInfo) { progress, downloaded, total ->
                        progressBar?.progress = progress
                        val downloadedMB = downloaded / (1024 * 1024)
                        val totalMB = total?.let { it / (1024 * 1024) }

                        val progressMessage = when (progress) {
                            -1 -> getString(R.string.extracting_archive)
                            -2 -> getString(R.string.downloading_archive_part, downloaded.toInt(), total?.toInt() ?: 0)
                            -3 -> getString(R.string.merging_archive_parts)
                            else -> {
                                if (total != null) {
                                    getString(R.string.downloading_database_progress_size,
                                        index + 1, databases.size, dbInfo.name, downloadedMB, totalMB)
                                } else {
                                    getString(R.string.downloading_database_progress_no_size,
                                        index + 1, databases.size, dbInfo.name, downloadedMB)
                                }
                            }
                        }
                        progressText?.text = progressMessage
                    }

                    dbItem?.let { item ->
                        if (item.dbType == DbType.SQLITE_FILE_CUSTOM || item.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM) {
                            withContext(Dispatchers.Main) {
                                dialog.dismiss()
                                try {
                                    viewModel.initializeSQLiteCustomHelper(item.path.toUri(), item.directPath)

                                    if (dbInfo.type == "custom-auto-mapping") {
                                        if (item.tableName != null && item.columnMap != null && item.columnMap.isNotEmpty()) {
                                            val finalDbItem = item.copy(
                                                tableName = item.tableName,
                                                columnMap = item.columnMap
                                            )
                                            viewModel.addDb(finalDbItem)
                                            showSnackbar(getString(R.string.auto_mapping_applied))
                                        } else {
                                            showCustomDbSetupDialog(
                                                dbType = item.dbType,
                                                type = item.type,
                                                path = item.path,
                                                directPath = item.directPath
                                            )
                                            showSnackbar(getString(R.string.auto_mapping_failed))
                                        }
                                    } else {
                                        showCustomDbSetupDialog(
                                            dbType = item.dbType,
                                            type = item.type,
                                            path = item.path,
                                            directPath = item.directPath
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("DbSetupFragment", "Failed to initialize SQLiteCustomHelper", e)
                                    showSnackbar(getString(R.string.error_reading_database))
                                }
                            }
                        } else {
                            viewModel.addDb(item)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) dialog.show()
                    dialog.dismiss()
                    if (!isCancelled) {
                        reloadFragment()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    if (e !is CancellationException) {
                        val errorMessage = when {
                            e.message?.contains("archive") == true -> getString(R.string.error_extracting_archive, e.message)
                            e.message?.contains("merg") == true -> getString(R.string.error_merging_parts, e.message)
                            else -> getString(R.string.error_downloading_database, e.message)
                        }
                        showSnackbar(errorMessage)
                    }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            PERMISSION_REQUEST_READ_EXTERNAL_STORAGE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    selectFile()
                } else {
                    showStoragePermissionDeniedDialog()
                }
                viewModel.setStoragePermissionMessageShown()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            }
            else -> {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        val notGrantedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(
                requireContext(),
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(notGrantedPermissions.toTypedArray())
        } else {
            selectFile()
        }
    }

    private fun selectFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                "content://com.android.externalstorage.documents/document/primary:".toUri()
            )
        }
        startActivityForResult(intent, REQUEST_SELECT_FILE)
    }

    private fun addDb() {
        val type = binding.spinnerDbType.editText?.text.toString()
        val dbType = when (type) {
            getString(R.string.db_type_sqlite_3wifi) -> DbType.SQLITE_FILE_3WIFI
            getString(R.string.db_type_sqlite_custom) -> DbType.SQLITE_FILE_CUSTOM
            getString(R.string.db_type_3wifi) -> DbType.WIFI_API
            getString(R.string.db_type_smartlink) -> DbType.SMARTLINK_SQLITE_FILE_3WIFI
            else -> throw IllegalArgumentException("Unknown database type")
        }

        var path = when (dbType) {
            DbType.SQLITE_FILE_3WIFI, DbType.SQLITE_FILE_CUSTOM -> binding.buttonSelectFile.tag as? String
            DbType.WIFI_API, DbType.SMARTLINK_SQLITE_FILE_3WIFI -> binding.textInputLink.editText?.text.toString()
            else -> null
        } ?: return

        if (path.isBlank()) {
            Snackbar.make(
                binding.root,
                getString(R.string.enter_valid_path_or_url),
                Snackbar.LENGTH_SHORT
            ).show()
            return
        }

        if (dbType == DbType.WIFI_API || dbType == DbType.SMARTLINK_SQLITE_FILE_3WIFI) {
            if (!path.startsWith("http://") && !path.startsWith("https://")) {
                path = "https://$path"
            }
            path = path.trimEnd('/')
        }

        when (dbType) {
            DbType.SQLITE_FILE_3WIFI -> {
                val uri = path.toUri()
                val directPath = viewModel.getSelectedDirectPath()
                try {
                    viewModel.initializeSQLite3WiFiHelper(uri, directPath)
                    val tableNames = viewModel.getTableNames() ?: emptyList()
                    if (!tableNames.contains("geo") || (!tableNames.contains("nets") && !tableNames.contains("base"))) {
                        Snackbar.make(
                            binding.root,
                            getString(R.string.invalid_3wifi_sqlite_structure),
                            Snackbar.LENGTH_LONG
                        ).show()
                        return
                    }
                    addDbItem(dbType, type, path, directPath, null, null, null)
                } catch (_: Exception) {
                    val message = if (directPath == null) {
                        getString(R.string.db_cache_warning, viewModel.getSelectedFileSize())
                    } else {
                        getString(R.string.db_direct_access_error)
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.warning)
                        .setMessage(message)
                        .setPositiveButton(R.string.continue_anyway) { _, _ ->
                            addDbItem(dbType, type, path, null, null)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            DbType.SQLITE_FILE_CUSTOM -> {
                val uri = path.toUri()
                val directPath = viewModel.getSelectedDirectPath()
                try {
                    viewModel.initializeSQLiteCustomHelper(uri, directPath)
                    showCustomDbSetupDialog(dbType, type, path, directPath)
                } catch (_: Exception) {
                    val message = if (directPath == null) {
                        getString(R.string.db_cache_warning, viewModel.getSelectedFileSize())
                    } else {
                        getString(R.string.db_direct_access_error)
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.warning)
                        .setMessage(message)
                        .setPositiveButton(R.string.continue_anyway) { _, _ ->
                            showCustomDbSetupDialog(dbType, type, path, null)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            DbType.WIFI_API -> {
                val authMethodText = binding.spinnerAuthMethod.editText?.text.toString()
                val authMethod = when (authMethodText) {
                    getString(R.string.auth_method_api_keys) -> AuthMethod.API_KEYS
                    getString(R.string.auth_method_login_password) -> AuthMethod.LOGIN_PASSWORD
                    else -> AuthMethod.NO_AUTH
                }

                when (authMethod) {
                    AuthMethod.API_KEYS -> {
                        val readKey = binding.textInputApiReadKey.editText?.text.toString().takeIf { it.isNotBlank() } ?: "000000000000"
                        val writeKey = binding.textInputApiWriteKey.editText?.text.toString().takeIf { it.isNotBlank() } ?: "000000000000"
                        addApiServerWithKeys(path, readKey, writeKey, authMethod)
                    }
                    AuthMethod.LOGIN_PASSWORD -> {
                        val login = binding.textInputLogin.editText?.text.toString()
                        val password = binding.textInputPassword.editText?.text.toString()
                        if (login.isNotBlank() && password.isNotBlank()) {
                            addApiServerWithLogin(path, login, password, authMethod)
                        } else {
                            showSnackbar(getString(R.string.enter_valid_path_or_url))
                            return
                        }
                    }
                    AuthMethod.NO_AUTH -> {
                        addApiServerWithKeys(path, "000000000000", "000000000000", authMethod)
                    }
                }
            }
            DbType.SMARTLINK_SQLITE_FILE_3WIFI, DbType.SMARTLINK_SQLITE_FILE_CUSTOM -> {
                viewModel.fetchSmartLinkDatabases(path)
                showSmartLinkDatabasesDialog()
            }
            DbType.LOCAL_APP_DB -> {
                addDbItem(dbType, type, path, null, null, null, null)
            }
        }
    }

    private fun addApiServerWithKeys(serverUrl: String, readKey: String, writeKey: String, authMethod: AuthMethod) {
        val newItem = DbItem(
            id = UUID.randomUUID().toString(),
            path = serverUrl,
            directPath = null,
            type = getString(R.string.db_type_3wifi),
            dbType = DbType.WIFI_API,
            apiReadKey = readKey,
            apiWriteKey = writeKey,
            authMethod = authMethod,
            originalSizeInMB = 0f,
            cachedSizeInMB = 0f
        )

        viewModel.addDb(newItem)

        lifecycleScope.launch {
            try {
                val apiHelper = API3WiFiHelper(requireContext(), serverUrl, readKey)
                val supportsMap = apiHelper.checkMapApiSupport()

                if (supportsMap) {
                    val currentList = viewModel.dbList.value.orEmpty().toMutableList()
                    val index = currentList.indexOfFirst { it.id == newItem.id }
                    if (index != -1) {
                        currentList[index] = currentList[index].copy(supportsMapApi = true)
                        viewModel.updateDbItem(currentList[index])
                        Log.d("DbSetupFragment", "Database ${newItem.id} supports map API")
                    }
                }
            } catch (e: Exception) {
                Log.e("DbSetupFragment", "Error checking map support", e)
            }
        }

        clearInputs()
        showSnackbar(getString(R.string.db_added_successfully))
    }

    private fun addApiServerWithLogin(serverUrl: String, login: String, password: String, authMethod: AuthMethod) {
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(R.layout.dialog_test_progress)
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            try {
                val (readKey, writeKey, userInfo) = getApiKeysFromLogin(serverUrl, login, password)

                progressDialog.dismiss()

                if (readKey != null) {
                    val newItem = DbItem(
                        id = UUID.randomUUID().toString(),
                        path = serverUrl,
                        directPath = null,
                        type = getString(R.string.db_type_3wifi),
                        dbType = DbType.WIFI_API,
                        apiReadKey = readKey,
                        apiWriteKey = writeKey,
                        login = login,
                        password = password,
                        authMethod = authMethod,
                        userNick = userInfo?.first,
                        userLevel = userInfo?.second,
                        originalSizeInMB = 0f,
                        cachedSizeInMB = 0f
                    )

                    viewModel.addDb(newItem)
                    lifecycleScope.launch {
                        try {
                            val apiHelper = API3WiFiHelper(requireContext(), serverUrl, readKey ?: "000000000000")
                            val supportsMap = apiHelper.checkMapApiSupport()

                            if (supportsMap) {
                                val currentList = viewModel.dbList.value.orEmpty().toMutableList()
                                val index = currentList.indexOfFirst { it.id == newItem.id }
                                if (index != -1) {
                                    currentList[index] = currentList[index].copy(supportsMapApi = true)
                                    viewModel.updateDbItem(currentList[index])
                                    Log.d("DbSetupFragment", "Database ${newItem.id} supports map API")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("DbSetupFragment", "Error checking map support", e)
                        }
                    }

                    clearInputs()

                    val userManager = UserManager(requireContext())
                    val levelText = userInfo?.second?.let { userManager.getTextGroup(it) } ?: ""
                    val userInfoText = getString(R.string.user_info, userInfo?.first ?: "", levelText)
                    binding.textViewUserInfo.text = userInfoText
                    binding.textViewUserInfo.visibility = View.VISIBLE

                    showSnackbar(getString(R.string.login_successful))
                } else {
                    showSnackbar(getString(R.string.login_failed))
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                showSnackbar(e.message ?: getString(R.string.login_failed))
            }
        }
    }

    private fun showCustomDbSetupDialog(dbType: DbType, type: String, path: String, directPath: String?) {
        val tableNames = viewModel.getCustomTableNames() ?: return

        try {
            viewModel.initializeSQLiteCustomHelper(path.toUri(), directPath)
        } catch (e: Exception) {
            Log.e("DbSetupFragment", "Failed to initialize SQLiteCustomHelper", e)
            showSnackbar(getString(R.string.error_reading_database))
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.select_table))
            .setItems(tableNames.toTypedArray()) { _, which ->
                val selectedTable = tableNames[which]
                viewModel.setSelectedTable(selectedTable)
                showColumnMappingDialog(dbType, type, path, directPath, selectedTable)
            }
            .show()
    }

    private fun showColumnMappingDialog(dbType: DbType, type: String, path: String, directPath: String?, tableName: String) {
        val columnNames = viewModel.getCustomColumnNames(tableName) ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_column_mapping, null)

        val spinners = listOf(
            dialogView.findViewById<Spinner>(R.id.spinnerEssid),
            dialogView.findViewById<Spinner>(R.id.spinnerMac),
            dialogView.findViewById<Spinner>(R.id.spinnerWifiPass),
            dialogView.findViewById<Spinner>(R.id.spinnerWpsPin),
            dialogView.findViewById<Spinner>(R.id.spinnerAdminPanel),
            dialogView.findViewById<Spinner>(R.id.spinnerLatitude),
            dialogView.findViewById<Spinner>(R.id.spinnerLongitude),
            dialogView.findViewById<Spinner>(R.id.spinnerSecurityType),
            dialogView.findViewById<Spinner>(R.id.spinnerTimestamp)
        )

        val columnNamesWithNotSpecified = listOf(getString(R.string.not_specified)) + columnNames
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, columnNamesWithNotSpecified)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinners.forEach { spinner ->
            spinner.adapter = adapter
            spinner.setSelection(0)
        }

        val buttonShowAdditionalFields = dialogView.findViewById<Button>(R.id.buttonShowAdditionalFields)
        val layoutAdditionalFields = dialogView.findViewById<LinearLayout>(R.id.layoutAdditionalFields)

        buttonShowAdditionalFields.setOnClickListener {
            if (layoutAdditionalFields.isVisible) {
                layoutAdditionalFields.visibility = View.GONE
                buttonShowAdditionalFields.text = getString(R.string.show_additional_fields)
            } else {
                layoutAdditionalFields.visibility = View.VISIBLE
                buttonShowAdditionalFields.text = getString(R.string.hide_additional_fields)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.map_columns))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val columnMap = mapOf(
                    "essid" to spinners[0].selectedItem.toString(),
                    "mac" to spinners[1].selectedItem.toString(),
                    "wifi_pass" to spinners[2].selectedItem.toString(),
                    "wps_pin" to spinners[3].selectedItem.toString(),
                    "admin_panel" to spinners[4].selectedItem.toString(),
                    "latitude" to spinners[5].selectedItem.toString(),
                    "longitude" to spinners[6].selectedItem.toString(),
                    "security_type" to spinners[7].selectedItem.toString(),
                    "timestamp" to spinners[8].selectedItem.toString()
                ).filter { it.value != getString(R.string.not_specified) }

                addDbItem(dbType, type, path, directPath, null, tableName, columnMap)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun addDbItem(dbType: DbType, type: String, path: String, directPath: String?, apiKey: String?, tableName: String? = null, columnMap: Map<String, String>? = null) {
        val newItem = DbItem(
            id = UUID.randomUUID().toString(),
            path = path,
            directPath = directPath,
            type = type,
            dbType = dbType,
            apiKey = apiKey,
            originalSizeInMB = viewModel.getSelectedFileSize(),
            cachedSizeInMB = 0f,
            tableName = tableName,
            columnMap = columnMap
        )

        viewModel.addDb(newItem)

        clearInputs()

        Toast.makeText(
            requireContext(),
            getString(R.string.restart_needed_for_full_update),
            Toast.LENGTH_LONG
        ).show()

        if (dbType == DbType.WIFI_API && apiKey != null) {
            showTestServerDialog(apiKey, path)
        } else if (dbType == DbType.SQLITE_FILE_CUSTOM) {
            showIndexPromptDialog(newItem)
        }
    }

    private fun showIndexPromptDialog(dbItem: DbItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.index_database)
            .setMessage(R.string.index_database_prompt)
            .setPositiveButton(R.string.yes) { _, _ ->
                showIndexingProgressDialog(dbItem)
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun showTestServerDialog(apiKey: String, serverUrl: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.test_server)
            .setMessage(R.string.test_server_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                showTestProgressDialog(apiKey, serverUrl)
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun showTestProgressDialog(apiKey: String, serverUrl: String) {
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(R.layout.dialog_test_progress)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val apiHelper = API3WiFiHelper(requireContext(), serverUrl, apiKey)
                val (pingResult, results) = apiHelper.testServer()
                progressDialog.dismiss()
                showTestResults(pingResult, results)
            } catch (e: Exception) {
                progressDialog.dismiss()
                showErrorSnackbar(e.message ?: getString(R.string.unknown_error))
            }
        }
    }

    private fun showTestResults(ping: Long, results: List<Pair<String, Pair<Boolean, String>>>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_test_results, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewTestResults)
        val pingTextView = dialogView.findViewById<TextView>(R.id.textViewPing)
        val serverStatusTextView = dialogView.findViewById<TextView>(R.id.textViewServerStatus)

        pingTextView.text = getString(R.string.ping_result, ping)
        serverStatusTextView.text = getString(R.string.server_status_available)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = TestResultsAdapter(results)

        val serverDomain = results.firstOrNull()?.first?.toUri()?.host ?: "Unknown"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.test_results_with_domain, serverDomain))
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showErrorSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.dismiss) { }
            .show()
    }

    private fun clearInputs() {
        binding.textInputLink.editText?.text?.clear()
        binding.textInputApiReadKey.editText?.text?.clear()
        binding.textInputApiWriteKey.editText?.text?.clear()
        binding.textInputLogin.editText?.text?.clear()
        binding.textInputPassword.editText?.text?.clear()
        binding.textViewUserInfo.visibility = View.GONE
        binding.buttonSelectFile.tag = null
        binding.textViewSelectedFile.visibility = View.GONE
    }

    private fun observeViewModel() {
        viewModel.dbList.observe(viewLifecycleOwner) { dbList ->
            dbListAdapter.submitList(dbList)
        }

        viewModel.errorEvent.observe(viewLifecycleOwner) { errorMessage ->
            when (errorMessage) {
                "missing_file_removed" -> {
                    Toast.makeText(context, getString(R.string.database_file_not_found_removed), Toast.LENGTH_LONG).show()
                }
                else -> {
                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getDisplayNameFromUri(uri: Uri): String {
        var displayName = "Unknown file"
        try {
            context?.contentResolver?.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex("_display_name")
                    if (nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        if (name != null) {
                            displayName = name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DbSetupFragment", "Error getting display name", e)
        }

        if (displayName == "Unknown file") {
            displayName = uri.lastPathSegment?.split("/")?.last() ?: "Unknown file"
        }

        return displayName
    }

    private fun getDirectPathFromUri(uri: Uri): String? {
        val context = context ?: return null
        return when {
            DocumentsContract.isDocumentUri(context, uri) -> {
                when {
                    isExternalStorageDocument(uri) -> {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val split = docId.split(":")
                        val type = split[0]
                        if ("primary".equals(type, ignoreCase = true)) {
                            "${Environment.getExternalStorageDirectory()}/${split[1]}"
                        } else {
                            val externalStorageVolumes: Array<out File> =
                                ContextCompat.getExternalFilesDirs(context, null)
                            for (file in externalStorageVolumes) {
                                val path = file.absolutePath
                                if (path.contains(type)) {
                                    return path.substringBefore("/Android") + "/${split[1]}"
                                }
                            }
                            null
                        }
                    }
                    isDownloadsDocument(uri) -> {
                        val docId = DocumentsContract.getDocumentId(uri)
                        when {
                            docId.startsWith("msf:") -> {
                                val contentUri = ContentUris.withAppendedId(
                                    "content://downloads/public_downloads".toUri(),
                                    docId.substringAfter("msf:").toLong()
                                )
                                getDataColumn(context, contentUri, null, null)
                            }
                            docId.startsWith("raw:") -> {
                                docId.substringAfter("raw:")
                            }
                            else -> {
                                try {
                                    val contentUri = ContentUris.withAppendedId(
                                        "content://downloads/public_downloads".toUri(),
                                        docId.toLong()
                                    )
                                    getDataColumn(context, contentUri, null, null)
                                } catch (_: NumberFormatException) {
                                    getDataColumn(context, uri, null, null)
                                }
                            }
                        }
                    }
                    isMediaDocument(uri) -> {
                        val docId = DocumentsContract.getDocumentId(uri)
                        val split = docId.split(":")
                        val type = split[0]
                        var contentUri: Uri? = null
                        when (type) {
                            "image" -> contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        }
                        val selection = "_id=?"
                        val selectionArgs = arrayOf(split[1])
                        getDataColumn(context, contentUri, selection, selectionArgs)
                    }
                    else -> null
                }
            }
            "content".equals(uri.scheme, ignoreCase = true) -> {
                if (isGooglePhotosUri(uri)) {
                    uri.lastPathSegment
                } else {
                    getDataColumn(context, uri, null, null)
                }
            }
            "file".equals(uri.scheme, ignoreCase = true) -> {
                uri.path
            }
            else -> null
        }
    }

    private fun updateDbSizes() {
        viewModel.dbList.value?.forEach { dbItem ->
            viewModel.updateDbSize(dbItem)
        }
    }

    private fun isGooglePhotosUri(uri: Uri): Boolean {
        return "com.google.android.apps.photos.content" == uri.authority
    }

    private fun getDataColumn(
        context: Context,
        uri: Uri?,
        selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        uri ?: return null
        val column = "_data"
        val projection = arrayOf(column)
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndexOrThrow(column)
                        return cursor.getString(columnIndex)
                    }
                }
        } catch (e: Exception) {
            Log.e("DbSetupFragment", "Error getting data column", e)
        }
        return null
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    private fun getFileSizeInMB(uri: Uri): Float {
        val fileDescriptor = context?.contentResolver?.openFileDescriptor(uri, "r")
        val fileSize = fileDescriptor?.statSize ?: 0
        fileDescriptor?.close()
        return fileSize.toFloat() / (1024 * 1024)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val REQUEST_SELECT_FILE = 1
        private const val PERMISSION_REQUEST_READ_EXTERNAL_STORAGE = 2
        private const val REQUEST_BACKUP_DB = 3
        private const val REQUEST_RESTORE_DB = 4
        private const val REQUEST_EXPORT_JSON = 6
        private const val REQUEST_EXPORT_CSV = 7
        private const val REQUEST_IMPORT_JSON = 8
        private const val REQUEST_IMPORT_CSV = 9
        private const val REQUEST_IMPORT_ROUTERSCAN = 10
    }
}
