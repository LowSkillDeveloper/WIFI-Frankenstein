package com.lsd.wififrankenstein.ui.welcome

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.databinding.FragmentWelcomeDatabasesBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.SmartLinkDbInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class WelcomeDatabasesFragment : Fragment() {

    private var _binding: FragmentWelcomeDatabasesBinding? = null
    private val binding get() = _binding!!
    private val dbSetupViewModel: DbSetupViewModel by activityViewModels()
    private val welcomeViewModel: WelcomeViewModel by activityViewModels()

    private lateinit var recommendedDatabasesAdapter: WelcomeDatabaseAdapter
    private lateinit var selectedDatabasesAdapter: WelcomeDatabaseAdapter
    private lateinit var apiServersAdapter: WelcomeDatabaseAdapter

    private var currentStep = 0
    private val totalSteps = 4
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (currentStep == 0 && (binding.recyclerViewRecommendedDatabases.visibility != View.VISIBLE)) {
            showNextStep()
        }
    }

    private val select3WiFiFileResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri, DbType.SQLITE_FILE_3WIFI)
            }
        }
    }

    private val selectCustomFileResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri, DbType.SQLITE_FILE_CUSTOM)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeDatabasesBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("LongLogTag")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupButtons()

        showStep(0)

        checkRecommendedDatabases()

        timeoutHandler.postDelayed(timeoutRunnable, 3000)

        dbSetupViewModel.dbList.observe(viewLifecycleOwner) { dbList ->
            val smartLinkDatabases = dbList.filter {
                it.dbType == DbType.SMARTLINK_SQLITE_FILE_3WIFI ||
                        it.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM ||
                        it.smartlinkType != null
            }
            (binding.recyclerViewSelectedDatabasesStep2.adapter as? SelectedDatabaseAdapter)?.submitList(smartLinkDatabases)
            binding.textViewSelectedDatabasesStep2Title.visibility =
                if (smartLinkDatabases.isNotEmpty()) View.VISIBLE else View.GONE

            val fileDBs = dbList.filter {
                it.dbType != DbType.WIFI_API &&
                        it.dbType != DbType.LOCAL_APP_DB
            }
            selectedDatabasesAdapter.submitList(fileDBs)

            val apiServers = dbList.filter { it.dbType == DbType.WIFI_API }
            apiServersAdapter.submitList(apiServers)

            Log.d("WelcomeDatabasesFragment", "Database list observer updated: " +
                    "SmartLink(${smartLinkDatabases.size}), " +
                    "Files(${fileDBs.size}), " +
                    "API(${apiServers.size})")
        }

        forceUpdateDbList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        _binding = null
    }

    private fun setupRecyclerViews() {
        recommendedDatabasesAdapter = WelcomeDatabaseAdapter(
            onAddDatabase = { database ->
                dbSetupViewModel.addDb(database)
                forceUpdateDbList()
            },
            isSelectedList = false
        )
        binding.recyclerViewRecommendedDatabases.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewRecommendedDatabases.adapter = recommendedDatabasesAdapter

        val selectedDatabasesStep2Adapter = SelectedDatabaseAdapter(
            onRemoveClick = { database ->
                dbSetupViewModel.removeDb(database.id)
                forceUpdateDbList()
            }
        )
        binding.recyclerViewSelectedDatabasesStep2.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSelectedDatabasesStep2.adapter = selectedDatabasesStep2Adapter

        selectedDatabasesAdapter = WelcomeDatabaseAdapter(
            onAddDatabase = { database ->
                dbSetupViewModel.removeDb(database.id)
                forceUpdateDbList()
            },
            isSelectedList = true
        )
        binding.recyclerViewSelectedDatabases.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSelectedDatabases.adapter = selectedDatabasesAdapter

        apiServersAdapter = WelcomeDatabaseAdapter(
            onAddDatabase = { database ->
                dbSetupViewModel.removeDb(database.id)
                forceUpdateDbList()
            },
            isSelectedList = true
        )
        binding.recyclerViewApiServers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewApiServers.adapter = apiServersAdapter
    }

    @SuppressLint("LongLogTag")
    private fun forceUpdateDbList() {
        lifecycleScope.launch {
            try {
                dbSetupViewModel.loadDbList()

                val dbList = dbSetupViewModel.dbList.value ?: emptyList()

                val smartLinkDatabases = dbList.filter {
                    it.dbType == DbType.SMARTLINK_SQLITE_FILE_3WIFI ||
                            it.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM ||
                            it.smartlinkType != null
                }

                val fileDBs = dbList.filter {
                    it.dbType != DbType.WIFI_API &&
                            it.dbType != DbType.LOCAL_APP_DB
                }

                val apiServers = dbList.filter { it.dbType == DbType.WIFI_API }

                withContext(Dispatchers.Main) {
                    selectedDatabasesAdapter.submitList(fileDBs)

                    (binding.recyclerViewSelectedDatabasesStep2.adapter as? SelectedDatabaseAdapter)?.submitList(smartLinkDatabases)
                    binding.textViewSelectedDatabasesStep2Title.visibility =
                        if (smartLinkDatabases.isNotEmpty()) View.VISIBLE else View.GONE

                    apiServersAdapter.submitList(apiServers)

                    Log.d("WelcomeDatabasesFragment", "Updated adapters with: " +
                            "SmartLink(${smartLinkDatabases.size}), " +
                            "Files(${fileDBs.size}), " +
                            "API(${apiServers.size})")
                }
            } catch (e: Exception) {
                Log.e("WelcomeDatabasesFragment", "Error updating database list", e)
            }
        }
    }

    private fun setupButtons() {
        binding.buttonAddSmartLink.setOnClickListener {
            val url = binding.editTextSmartLinkUrl.text.toString()
            if (url.isNotEmpty()) {
                fetchSmartLinkDatabases(url)
            } else {
                showError(getString(R.string.db_invalid_url))
            }
        }

        binding.buttonSelect3WiFiDb.setOnClickListener {
            selectFile(select3WiFiFileResultLauncher)
        }

        binding.buttonSelectCustomDb.setOnClickListener {
            selectFile(selectCustomFileResultLauncher)
        }

        binding.buttonAddApi.setOnClickListener {
            val url = binding.editTextApiUrl.text.toString()
            val apiKey = binding.editTextApiKey.text.toString().takeIf { it.isNotEmpty() } ?: "000000000000"

            if (url.isNotEmpty()) {
                addApiServer(url, apiKey)
            } else {
                showError(getString(R.string.db_invalid_url))
            }
        }
    }

    private fun showStep(step: Int) {
        currentStep = step.coerceIn(0, totalSteps - 1)

        binding.viewFlipperDatabases.displayedChild = currentStep

        forceUpdateDbList()

        (activity as? WelcomeActivity)?.updateNavigationButtons(
            showPrev = currentStep > 0,
            showSkip = true,
            showNext = true,
            skipText = when(currentStep) {
                0 -> getString(R.string.db_step1_skip)
                1 -> getString(R.string.db_step2_skip)
                2 -> getString(R.string.db_step3_skip)
                3 -> getString(R.string.db_step4_skip)
                else -> getString(R.string.skip)
            },
            nextText = when(currentStep) {
                0 -> getString(R.string.db_step1_next)
                1 -> getString(R.string.db_step2_next)
                2 -> getString(R.string.db_step3_next)
                3 -> getString(R.string.db_step4_finish)
                else -> getString(R.string.next)
            }
        )
    }

    fun goNext() {
        if (currentStep == 3) {

            val url = binding.editTextApiUrl.text.toString()
            val apiKey = binding.editTextApiKey.text.toString().takeIf { it.isNotEmpty() } ?: "000000000000"

            if (url.isNotEmpty()) {
                addApiServer(url, apiKey)
            }

            (activity as? WelcomeActivity)?.navigateToNextFragment()
        } else if (currentStep < totalSteps - 1) {
            showNextStep()
        } else {
            (activity as? WelcomeActivity)?.navigateToNextFragment()
        }
    }

    fun goBack() {
        showPreviousStep()
    }

    private fun showNextStep() {
        if (currentStep < totalSteps - 1) {
            showStep(currentStep + 1)
        } else {
            (activity as? WelcomeActivity)?.navigateToNextFragment()
        }
    }

    private fun showPreviousStep() {
        if (currentStep > 0) {
            showStep(currentStep - 1)
        }
    }

    private fun checkRecommendedDatabases() {
        binding.progressBarStep1.visibility = View.VISIBLE
        binding.textViewNoRecommendedDatabases.visibility = View.GONE
        binding.recyclerViewRecommendedDatabases.visibility = View.GONE

        val recommendedDbUrl = "https://raw.githubusercontent.com/LowSkillDeveloper/WIFI-Frankenstein/refs/heads/service/recommended-databases.json"

        lifecycleScope.launch {
            try {
                val success = withTimeoutOrNull(2500) {
                    dbSetupViewModel.fetchSmartLinkDatabases(recommendedDbUrl)
                    true
                } == true

                if (success) {
                    dbSetupViewModel.smartLinkDatabases.observe(viewLifecycleOwner) { databases ->
                        binding.progressBarStep1.visibility = View.GONE
                        timeoutHandler.removeCallbacks(timeoutRunnable)

                        if (databases.isNotEmpty()) {
                            binding.textViewStep1Description.text = getString(R.string.db_step1_found_databases)
                            binding.recyclerViewRecommendedDatabases.visibility = View.VISIBLE

                            val dbItems = databases.map { createDbItemFromSmartLinkInfo(it) }
                            recommendedDatabasesAdapter.submitList(dbItems)
                        } else {
                            binding.textViewNoRecommendedDatabases.visibility = View.VISIBLE
                            binding.textViewStep1Description.text = getString(R.string.db_step1_no_databases)
                        }
                    }
                } else {
                    binding.progressBarStep1.visibility = View.GONE
                    binding.textViewNoRecommendedDatabases.visibility = View.VISIBLE
                    binding.textViewNoRecommendedDatabases.text = getString(R.string.db_error_loading)
                    binding.textViewStep1Description.text = getString(R.string.db_check_connection)
                }
            } catch (_: Exception) {
                binding.progressBarStep1.visibility = View.GONE
                binding.textViewNoRecommendedDatabases.visibility = View.VISIBLE
                binding.textViewNoRecommendedDatabases.text = getString(R.string.db_error_loading)
                binding.textViewStep1Description.text = getString(R.string.db_check_connection)
            }
        }
    }

    @SuppressLint("LongLogTag")
    private fun fetchSmartLinkDatabases(url: String) {
        lifecycleScope.launch {
            try {
                showProgress(true)

                dbSetupViewModel.smartLinkDatabases.removeObservers(viewLifecycleOwner)

                dbSetupViewModel.fetchSmartLinkDatabases(url)

                withContext(Dispatchers.IO) {
                    kotlin.runCatching {
                        delay(1000)
                    }
                }

                withContext(Dispatchers.Main) {
                    showProgress(false)
                    val databases = dbSetupViewModel.smartLinkDatabases.value

                    if (databases != null && databases.isNotEmpty()) {

                        showSmartLinkDatabasesDialog()

                        forceUpdateDbList()
                    } else {
                        showError(getString(R.string.db_step1_no_databases))
                    }
                }
            } catch (e: Exception) {
                Log.e("WelcomeDatabasesFragment", "Error fetching SmartLink databases", e)
                showProgress(false)
                showError(getString(R.string.db_error_loading))
            }
        }
    }

    private fun showSmartLinkDatabasesDialog() {
        val databases = dbSetupViewModel.smartLinkDatabases.value ?: return

        val items = databases.map {
            "${it.name} (${if (it.type == "3wifi") getString(R.string.type_3wifi) else getString(R.string.type_custom)})"
        }.toTypedArray()
        val checkedItems = BooleanArray(items.size) { false }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_databases_to_download)
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(R.string.download) { _, _ ->
                val selectedDatabases = databases.filterIndexed { index, _ -> checkedItems[index] }
                if (selectedDatabases.isNotEmpty()) {
                    showDownloadProgressDialog(selectedDatabases)
                } else {
                    showError(getString(R.string.no_databases_selected))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @SuppressLint("LongLogTag")
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

                    progressBar?.progress = 0

                    val dbItem = dbSetupViewModel.downloadSmartLinkDatabase(dbInfo) { progress, downloaded, total ->
                        progressBar?.progress = progress
                        val downloadedMB = downloaded / (1024 * 1024)
                        val totalMB = total?.let { it / (1024 * 1024) }

                        val progressMessage = if (total != null) {
                            getString(R.string.downloading_database_progress_size,
                                index + 1, databases.size, dbInfo.name, downloadedMB, totalMB)
                        } else {
                            getString(R.string.downloading_database_progress_no_size,
                                index + 1, databases.size, dbInfo.name, downloadedMB)
                        }
                        progressText?.text = progressMessage
                    }
                    dbItem?.let { item ->
                        if (item.dbType == DbType.SQLITE_FILE_CUSTOM) {
                            withContext(Dispatchers.Main) {
                                dialog.dismiss()
                                try {
                                    dbSetupViewModel.initializeSQLiteCustomHelper(item.path.toUri(), item.directPath)
                                    showCustomDbSetupDialog(
                                        dbType = item.dbType,
                                        type = item.type,
                                        path = item.path,
                                        directPath = item.directPath
                                    )
                                } catch (e: Exception) {
                                    Log.e("WelcomeDatabasesFragment", "Failed to initialize SQLiteCustomHelper", e)
                                    showSnackbar(getString(R.string.error_reading_database))
                                }
                            }
                        } else {
                            val existingDb = dbSetupViewModel.dbList.value?.find { it.id == item.id }
                            if (existingDb == null) {
                                dbSetupViewModel.addDb(item)
                                welcomeViewModel.addSelectedDatabase(item)
                            }
                            withContext(Dispatchers.Main) {
                                forceUpdateDbList()
                                showSuccess(getString(R.string.db_added_successfully))
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) dialog.show()
                    dialog.dismiss()
                    if (!isCancelled) {
                        forceUpdateDbList()
                        showSuccess(getString(R.string.download_completed))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    if (e !is CancellationException) {
                        showSnackbar(getString(R.string.error_downloading_database, e.message))
                        forceUpdateDbList()
                    }
                }
            }
        }
    }

    @SuppressLint("LongLogTag")
    private fun showCustomDbSetupDialog(dbType: DbType, type: String, path: String, directPath: String?) {
        try {
            val tableNames = dbSetupViewModel.getCustomTableNames() ?: return

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.select_table))
                .setItems(tableNames.toTypedArray()) { _, which ->
                    val selectedTable = tableNames[which]
                    dbSetupViewModel.setSelectedTable(selectedTable)
                    showColumnMappingDialog(dbType, type, path, directPath, selectedTable)
                }
                .show()
        } catch (e: Exception) {
            Log.e("WelcomeDatabasesFragment", "Error showing custom DB dialog", e)
            showSnackbar(getString(R.string.error_opening_file))
        }
    }

    private fun showColumnMappingDialog(dbType: DbType, type: String, path: String, directPath: String?, tableName: String) {
        val columnNames = dbSetupViewModel.getCustomColumnNames(tableName) ?: return
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

                val dbItem = DbItem(
                    id = UUID.randomUUID().toString(),
                    path = path,
                    directPath = directPath,
                    type = type,
                    dbType = dbType,
                    originalSizeInMB = 0f,
                    cachedSizeInMB = 0f,
                    tableName = tableName,
                    columnMap = columnMap
                )

                dbSetupViewModel.addDb(dbItem)
                welcomeViewModel.addSelectedDatabase(dbItem)
                showNextStep()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun selectFile(launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        launcher.launch(intent)
    }

    private fun handleSelectedFile(uri: Uri, dbType: DbType) {
        try {
            context?.contentResolver?.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val directPath = dbSetupViewModel.getDirectPathFromUri(uri)

            uri.lastPathSegment?.split("/")?.last() ?: "database"
            val dbItem = DbItem(
                id = UUID.randomUUID().toString(),
                path = uri.toString(),
                directPath = directPath,
                type = if (dbType == DbType.SQLITE_FILE_3WIFI)
                    getString(R.string.db_type_sqlite_3wifi)
                else
                    getString(R.string.db_type_sqlite_custom),
                dbType = dbType,
                originalSizeInMB = getFileSizeInMB(uri),
                cachedSizeInMB = 0f
            )

            if (dbType == DbType.SQLITE_FILE_3WIFI) {
                lifecycleScope.launch {
                    dbSetupViewModel.initializeSQLite3WiFiHelper(uri, directPath)
                    val tableNames = dbSetupViewModel.getTableNames() ?: emptyList()

                    if (!tableNames.contains("geo") || (!tableNames.contains("nets") && !tableNames.contains("base"))) {
                        showError(getString(R.string.invalid_3wifi_sqlite_structure))
                    } else {
                        dbSetupViewModel.addDb(dbItem)
                        welcomeViewModel.addSelectedDatabase(dbItem)
                        forceUpdateDbList()
                        showSuccess(getString(R.string.db_added_successfully))
                    }
                }
            } else {
                dbSetupViewModel.initializeSQLiteCustomHelper(uri, directPath)
                showCustomDbSetupDialog(dbType, dbItem.type, uri.toString(), directPath)
            }
        } catch (e: Exception) {
            showError(e.message ?: getString(R.string.error_opening_file))
        }
    }

    @SuppressLint("LongLogTag")
    private fun addApiServer(url: String, apiKey: String) {
        var serverUrl = url
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://$serverUrl"
        }
        serverUrl = serverUrl.trimEnd('/')

        val dbItem = DbItem(
            id = UUID.randomUUID().toString(),
            path = serverUrl,
            directPath = null,
            type = getString(R.string.db_type_3wifi),
            dbType = DbType.WIFI_API,
            apiKey = apiKey,
            originalSizeInMB = 0f,
            cachedSizeInMB = 0f
        )

        lifecycleScope.launch {
            try {
                dbSetupViewModel.addDb(dbItem)
                welcomeViewModel.addSelectedDatabase(dbItem)
                delay(100)

                val dbList = dbSetupViewModel.dbList.value ?: emptyList()
                val apiServers = dbList.filter { it.dbType == DbType.WIFI_API }

                withContext(Dispatchers.Main) {
                    apiServersAdapter.submitList(apiServers)

                    binding.editTextApiUrl.text?.clear()
                    binding.editTextApiKey.text?.clear()

                    showSuccess(getString(R.string.db_added_successfully))

                    Log.d("WelcomeDatabasesFragment", "API server added. Total API servers: ${apiServers.size}")
                }
            } catch (e: Exception) {
                Log.e("WelcomeDatabasesFragment", "Error adding API server", e)
                showError(getString(R.string.operation_failed))
            }
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
            updateUrl = smartLinkInfo.downloadUrls.firstOrNull() ?: "",
            smartlinkType = smartLinkInfo.type
        )
    }

    private fun getFileSizeInMB(uri: Uri): Float {
        val fileDescriptor = context?.contentResolver?.openFileDescriptor(uri, "r")
        val fileSize = fileDescriptor?.statSize ?: 0
        fileDescriptor?.close()
        return fileSize.toFloat() / (1024 * 1024)
    }

    private fun showProgress(show: Boolean) {
        binding.progressBarStep1.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showSuccess(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        fun newInstance() = WelcomeDatabasesFragment()
    }
}