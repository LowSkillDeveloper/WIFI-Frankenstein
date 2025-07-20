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
import android.util.Log
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
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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

    private fun importFromRouterScan() {
        // Заглушка
        showSnackbar(getString(R.string.routerscan_import_not_implemented))
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
                        importFromRouterScan()
                        reloadFragment()
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
                    binding.textInputApiKey.visibility = View.GONE
                    binding.textViewApiKeyHint.visibility = View.GONE
                    checkStoragePermission()
                }
                getString(R.string.db_type_3wifi) -> {
                    binding.textInputLink.visibility = View.VISIBLE
                    binding.buttonSelectFile.visibility = View.GONE
                    binding.textInputApiKey.visibility = View.VISIBLE
                    binding.textViewApiKeyHint.visibility = View.VISIBLE
                }
                getString(R.string.db_type_smartlink) -> {
                    binding.textInputLink.visibility = View.VISIBLE
                    binding.buttonSelectFile.visibility = View.GONE
                    binding.textInputApiKey.visibility = View.GONE
                    binding.textViewApiKeyHint.visibility = View.GONE
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
                    binding.textInputApiKey.visibility = View.GONE
                    binding.textViewApiKeyHint.visibility = View.GONE
                    checkStoragePermission()
                }
                2 -> { // 3WiFi API
                    binding.textInputLink.visibility = View.VISIBLE
                    binding.buttonSelectFile.visibility = View.GONE
                    binding.textInputApiKey.visibility = View.VISIBLE
                    binding.textViewApiKeyHint.visibility = View.VISIBLE
                }
                3 -> { // SmartLinkDB
                    binding.textInputLink.visibility = View.VISIBLE
                    binding.buttonSelectFile.visibility = View.GONE
                    binding.textInputApiKey.visibility = View.GONE
                    binding.textViewApiKeyHint.visibility = View.GONE
                    binding.buttonAdd.text = getString(R.string.fetch_smartlink_databases)
                }
            }
            binding.buttonAdd.visibility = View.VISIBLE
        }
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
                        if (item.dbType == DbType.SQLITE_FILE_CUSTOM) {
                            withContext(Dispatchers.Main) {
                                dialog.dismiss()
                                try {
                                    viewModel.initializeSQLiteCustomHelper(item.path.toUri(), item.directPath)
                                    showCustomDbSetupDialog(
                                        dbType = item.dbType,
                                        type = item.type,
                                        path = item.path,
                                        directPath = item.directPath
                                    )
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

        val apiKey = if (dbType == DbType.WIFI_API) {
            binding.textInputApiKey.editText?.text.toString().takeIf { it.isNotBlank() }
                ?: "000000000000"
        } else {
            null
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
                addDbItem(dbType, type, path, null, apiKey, null, null)
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
        binding.textInputApiKey.editText?.text?.clear()
        binding.buttonSelectFile.tag = null
    }

    private fun observeViewModel() {
        viewModel.dbList.observe(viewLifecycleOwner) { dbList ->
            dbListAdapter.submitList(dbList)
        }

        viewModel.errorEvent.observe(viewLifecycleOwner) { errorMessage ->
            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
        }
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
