package com.lsd.wififrankenstein.ui.welcome

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.databinding.FragmentWelcomeDatabasesBinding
import com.lsd.wififrankenstein.ui.dbsetup.ApiServerHelper
import com.lsd.wififrankenstein.ui.dbsetup.AuthMethod
import com.lsd.wififrankenstein.ui.dbsetup.ColumnAutoMapper
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
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class WelcomeDatabasesFragment : Fragment() {

    private var _binding: FragmentWelcomeDatabasesBinding? = null
    private val binding get() = _binding!!
    private val dbSetupViewModel: DbSetupViewModel by lazy {
        DbSetupViewModel.getInstance(requireActivity().application)
    }
    private val welcomeViewModel: WelcomeViewModel by activityViewModels()

    private lateinit var selectedDatabasesAdapter: WelcomeDatabaseAdapter

    private val selectFileResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> handleSelectedFile(uri) }
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

        (activity as? WelcomeActivity)?.setBottomHint(null)

        setupRecyclerViews()
        setupCardClicks()
        loadSelectedDatabases()
        setupWarningObserver()
    }

    private fun setupRecyclerViews() {
        selectedDatabasesAdapter = WelcomeDatabaseAdapter(
            onAddDatabase = { db ->
                dbSetupViewModel.removeDb(db.id)
                welcomeViewModel.removeSelectedDatabase(db.id)
                refreshDbList()
            },
            isSelectedList = true
        )
        binding.recyclerViewSelected.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewSelected.adapter = selectedDatabasesAdapter
    }

    private fun setupCardClicks() {
        binding.cardDownload.setOnClickListener { showRecommendedBottomSheet() }
        binding.cardAddFile.setOnClickListener { pickFile() }
        binding.cardAddUrl.setOnClickListener { showUrlInputBottomSheet() }
        binding.cardAddApiServer.setOnClickListener { showApiConfigBottomSheet() }
    }

    private fun loadSelectedDatabases() {
        lifecycleScope.launch {
            dbSetupViewModel.loadDbList()
            refreshDbList()
        }
    }

    private fun refreshDbList() {
        val dbList = dbSetupViewModel.dbList.value ?: emptyList()
        val fileDbs =
            dbList.filter { it.dbType != DbType.LOCAL_APP_DB }
        val hasDbs = fileDbs.isNotEmpty()

        selectedDatabasesAdapter.submitList(fileDbs)
        binding.textViewSelectedTitle.visibility = if (hasDbs) View.VISIBLE else View.GONE
        binding.recyclerViewSelected.visibility = if (hasDbs) View.VISIBLE else View.GONE
        binding.textViewNoDatabases.visibility = if (hasDbs) View.GONE else View.VISIBLE

        val currentSelected = welcomeViewModel.selectedDatabases.value.orEmpty()
        val currentIds = currentSelected.map { it.id }.toSet()
        val fileDbIds = fileDbs.map { it.id }.toSet()

        currentSelected.filter { it.id !in fileDbIds }
            .forEach { welcomeViewModel.removeSelectedDatabase(it.id) }
        fileDbs.filter { it.id !in currentIds }.forEach { welcomeViewModel.addSelectedDatabase(it) }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        selectFileResultLauncher.launch(intent)
    }

    @SuppressLint("LongLogTag")
    private fun handleSelectedFile(uri: Uri) {
        try {
            context?.contentResolver?.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            autoDetectAndAdd(uri)
        } catch (e: Exception) {
            showError(getString(R.string.error_opening_file))
        }
    }

    @SuppressLint("LongLogTag")
    private fun autoDetectAndAdd(uri: Uri) {
        val directPath = dbSetupViewModel.getDirectPathFromUri(uri)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(R.layout.dialog_copy_progress)
            .setCancelable(false)
            .create()
        dialog.show()

        val progressBar = dialog.findViewById<ProgressBar>(R.id.progressBarCopy)
        val progressText = dialog.findViewById<TextView>(R.id.textViewCopyProgress)
        val cancelButton = dialog.findViewById<Button>(R.id.buttonCancelCopy)

        var isCancelled = false
        var job: Job? = null

        cancelButton?.setOnClickListener {
            isCancelled = true
            job?.cancel()
            dialog.dismiss()
        }

        job = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = dbSetupViewModel.initializeSQLite3WiFiHelperWithProgress(
                    uri, null,
                    { progress, bytesCopied, totalBytes ->
                        if (!isCancelled && progressBar != null) {
                            val copiedMB = String.format("%.1f", bytesCopied / (1024f * 1024f))
                            val totalMB = if (totalBytes > 0) String.format(
                                "%.1f",
                                totalBytes / (1024f * 1024f)
                            ) else "0.0"
                            val progressMsg = if (totalBytes > 0)
                                getString(
                                    R.string.copying_file_progress_size,
                                    progress,
                                    copiedMB,
                                    totalMB
                                )
                            else
                                getString(R.string.copying_file_progress, progress)
                            progressBar.post {
                                progressBar.progress = progress
                                progressBar.isIndeterminate = false
                                progressText?.text = progressMsg
                            }
                        }
                    },
                    {}
                )

                if (!isCancelled && success) {
                    val tableNames = dbSetupViewModel.getTableNames() ?: emptyList()
                    if (tableNames.contains("geo") && (tableNames.contains("nets") || tableNames.contains(
                            "base"
                        ))
                    ) {
                        val dbItem = DbItem(
                            id = UUID.randomUUID().toString(),
                            path = uri.toString(),
                            directPath = directPath,
                            type = getString(R.string.db_type_sqlite_3wifi),
                            dbType = DbType.SQLITE_FILE_P3WIFI,
                            originalSizeInMB = getFileSizeInMB(uri),
                            cachedSizeInMB = 0f
                        )
                        withContext(Dispatchers.Main) {
                            dialog.dismiss()
                            dbSetupViewModel.addDb(dbItem)
                            welcomeViewModel.addSelectedDatabase(dbItem)
                            refreshDbList()
                            showSuccess(getString(R.string.db_added_successfully))
                        }
                    } else {
                        withContext(Dispatchers.Main) { dialog.dismiss() }
                        val dbItem = DbItem(
                            id = UUID.randomUUID().toString(),
                            path = uri.toString(),
                            directPath = directPath,
                            type = getString(R.string.db_type_sqlite_custom),
                            dbType = DbType.SQLITE_FILE_CUSTOM,
                            originalSizeInMB = getFileSizeInMB(uri),
                            cachedSizeInMB = 0f
                        )
                        processFileAsCustom(uri, directPath, dbItem)
                    }
                } else if (!isCancelled) {
                    withContext(Dispatchers.Main) { dialog.dismiss() }
                    val dbItem = DbItem(
                        id = UUID.randomUUID().toString(),
                        path = uri.toString(),
                        directPath = directPath,
                        type = getString(R.string.db_type_sqlite_custom),
                        dbType = DbType.SQLITE_FILE_CUSTOM,
                        originalSizeInMB = getFileSizeInMB(uri),
                        cachedSizeInMB = 0f
                    )
                    processFileAsCustom(uri, directPath, dbItem)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) dialog.dismiss()
                }
                if (_binding == null) return@launch
                val dbItem = DbItem(
                    id = UUID.randomUUID().toString(),
                    path = uri.toString(),
                    directPath = directPath,
                    type = getString(R.string.db_type_sqlite_custom),
                    dbType = DbType.SQLITE_FILE_CUSTOM,
                    originalSizeInMB = getFileSizeInMB(uri),
                    cachedSizeInMB = 0f
                )
                processFileAsCustom(uri, directPath, dbItem)
            } finally {
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) dialog.dismiss()
                }
            }
        }
    }

    private fun processFileAsCustom(uri: Uri, directPath: String?, dbItem: DbItem) {
        val cachedPath = dbSetupViewModel.getCached3WiFiDbPath()
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                dbSetupViewModel.initializeSQLiteCustomHelper(uri, cachedPath ?: directPath)
                val tableNames = dbSetupViewModel.getCustomTableNames()
                if (tableNames != null && tableNames.isNotEmpty()) {
                    showCustomDbSetupDialog(dbItem, tableNames)
                } else {
                    if (_binding == null) return@launch
                    showError(getString(R.string.invalid_3wifi_sqlite_structure))
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                showError(e.message ?: getString(R.string.error_opening_file))
            }
        }
    }

    private fun showUrlInputBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_url_input, null)
        dialog.setContentView(view)

        val editTextUrl =
            view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextUrl)
        val buttonCancel =
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonCancelUrl)
        val buttonDownload =
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonDownloadUrl)

        buttonCancel.setOnClickListener { dialog.dismiss() }

        buttonDownload.setOnClickListener {
            val url = editTextUrl.text?.toString()?.trim() ?: return@setOnClickListener
            if (url.isBlank()) {
                showError(getString(R.string.db_invalid_url))
                return@setOnClickListener
            }
            dialog.dismiss()
            lifecycleScope.launch {
                try {
                    val databases = dbSetupViewModel.fetchSmartLinkDatabases(url)
                    if (databases != null && databases.isNotEmpty()) {
                        showMultiSelectDialog(databases)
                    } else {
                        if (_binding == null) return@launch
                        showError(getString(R.string.db_step1_no_databases))
                    }
                } catch (e: Exception) {
                    if (_binding == null) return@launch
                    showError(getString(R.string.db_error_loading))
                }
            }
        }

        dialog.show()
    }

    private fun showApiConfigBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_api_config, null)
        dialog.setContentView(view)

        val editTextApiUrl =
            view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextApiUrl)
        val textInputApiUrl =
            view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textInputApiUrl)
        editTextApiUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val url = s?.toString() ?: ""
                when {
                    url.isBlank() -> {
                        textInputApiUrl.setEndIconDrawable(
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                requireContext(),
                                R.drawable.ic_web
                            )
                        )
                    }

                    url.startsWith("http://") || url.startsWith("https://") -> {
                        textInputApiUrl.setEndIconDrawable(
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                requireContext(),
                                R.drawable.ic_check
                            )
                        )
                    }

                    else -> {
                        textInputApiUrl.setEndIconDrawable(
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                requireContext(),
                                R.drawable.ic_close
                            )
                        )
                    }
                }
            }
        })
        val autoComplete = view.findViewById<AutoCompleteTextView>(R.id.autoCompleteAuthMethod)
        val textInputReadKey =
            view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textInputApiReadKey)
        val textInputWriteKey =
            view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textInputApiWriteKey)
        val textInputLogin =
            view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textInputLogin)
        val textInputPassword =
            view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textInputPassword)
        val textViewUserInfo = view.findViewById<TextView>(R.id.textViewUserInfo)
        val buttonAdd =
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonAddApi)

        val authMethods = arrayOf(
            getString(R.string.auth_method_api_keys),
            getString(R.string.auth_method_login_password),
            getString(R.string.auth_method_no_auth)
        )
        val spinnerAdapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authMethods)
        autoComplete.setAdapter(spinnerAdapter)

        autoComplete.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> {
                    textInputReadKey.visibility = View.VISIBLE
                    textInputWriteKey.visibility = View.VISIBLE
                    textInputLogin.visibility = View.GONE
                    textInputPassword.visibility = View.GONE
                    textViewUserInfo.visibility = View.GONE
                }

                1 -> {
                    textInputReadKey.visibility = View.GONE
                    textInputWriteKey.visibility = View.GONE
                    textInputLogin.visibility = View.VISIBLE
                    textInputPassword.visibility = View.VISIBLE
                    textViewUserInfo.visibility = View.GONE
                }

                2 -> {
                    textInputReadKey.visibility = View.GONE
                    textInputWriteKey.visibility = View.GONE
                    textInputLogin.visibility = View.GONE
                    textInputPassword.visibility = View.GONE
                    textViewUserInfo.visibility = View.GONE
                }
            }
        }
        autoComplete.setText(authMethods[0], false)
        textInputReadKey.visibility = View.VISIBLE
        textInputWriteKey.visibility = View.VISIBLE

        buttonAdd.setOnClickListener {
            val url = editTextApiUrl.text.toString()
            if (url.isNotEmpty()) {
                val authMethodText = autoComplete.text.toString()
                val authMethod = when (authMethodText) {
                    getString(R.string.auth_method_api_keys) -> AuthMethod.API_KEYS
                    getString(R.string.auth_method_login_password) -> AuthMethod.LOGIN_PASSWORD
                    else -> AuthMethod.NO_AUTH
                }

                when (authMethod) {
                    AuthMethod.API_KEYS -> {
                        val readKey =
                            textInputReadKey.editText?.text.toString().takeIf { it.isNotBlank() }
                                ?: "000000000000"
                        val writeKey =
                            textInputWriteKey.editText?.text.toString().takeIf { it.isNotBlank() }
                                ?: "000000000000"
                        val dbItem = ApiServerHelper.createDbItemWithKeys(
                            url,
                            readKey,
                            writeKey,
                            authMethod,
                            getString(R.string.db_type_3wifi)
                        )
                        lifecycleScope.launch {
                            try {
                                dbSetupViewModel.addDb(dbItem)
                                welcomeViewModel.addSelectedDatabase(dbItem)
                                delay(100)
                                refreshDbList()
                                dialog.dismiss()
                                if (_binding == null) return@launch
                                showSuccess(getString(R.string.db_added_successfully))
                            } catch (e: Exception) {
                                if (_binding == null) return@launch
                                showError(getString(R.string.operation_failed))
                            }
                        }
                    }

                    AuthMethod.LOGIN_PASSWORD -> {
                        val login = textInputLogin.editText?.text.toString()
                        val password = textInputPassword.editText?.text.toString()
                        if (login.isNotBlank() && password.isNotBlank()) {
                            val progressDialog = MaterialAlertDialogBuilder(requireContext())
                                .setView(R.layout.dialog_test_progress)
                                .setCancelable(false)
                                .create()
                            progressDialog.show()

                            lifecycleScope.launch {
                                try {
                                    val (readKey, writeKey, userInfo) = ApiServerHelper.getApiKeysFromLogin(
                                        serverUrl = url,
                                        login = login,
                                        password = password
                                    ) { error ->
                                        com.lsd.wififrankenstein.ui.dbsetup.UserManager(
                                            requireContext()
                                        ).getErrorDesc(error)
                                    }

                                    progressDialog.dismiss()

                                    if (readKey != null) {
                                        val dbItem = ApiServerHelper.createDbItemWithLogin(
                                            serverUrl = url,
                                            readKey = readKey,
                                            writeKey = writeKey ?: "",
                                            login = login,
                                            password = password,
                                            authMethod = authMethod,
                                            userInfo = userInfo,
                                            typeString = getString(R.string.db_type_3wifi)
                                        )
                                        dbSetupViewModel.addDb(dbItem)
                                        welcomeViewModel.addSelectedDatabase(dbItem)
                                        delay(100)
                                        refreshDbList()
                                        dialog.dismiss()
                                        if (_binding == null) return@launch
                                        showSuccess(getString(R.string.login_successful))
                                    } else {
                                        if (_binding == null) return@launch
                                        showError(getString(R.string.login_failed))
                                    }
                                } catch (e: Exception) {
                                    progressDialog.dismiss()
                                    if (_binding == null) return@launch
                                    showError(e.message ?: getString(R.string.login_failed))
                                }
                            }
                        } else {
                            showError(getString(R.string.enter_valid_path_or_url))
                        }
                    }

                    AuthMethod.NO_AUTH -> {
                        val dbItem = ApiServerHelper.createDbItemWithKeys(
                            url,
                            "000000000000",
                            "000000000000",
                            authMethod,
                            getString(R.string.db_type_3wifi)
                        )
                        lifecycleScope.launch {
                            try {
                                dbSetupViewModel.addDb(dbItem)
                                welcomeViewModel.addSelectedDatabase(dbItem)
                                delay(100)
                                refreshDbList()
                                dialog.dismiss()
                                if (_binding == null) return@launch
                                showSuccess(getString(R.string.db_added_successfully))
                            } catch (e: Exception) {
                                if (_binding == null) return@launch
                                showError(getString(R.string.operation_failed))
                            }
                        }
                    }
                }
            } else {
                showError(getString(R.string.db_invalid_url))
            }
        }

        dialog.show()
    }

    @SuppressLint("LongLogTag")
    private fun showRecommendedBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_recommended_dbs, null)
        dialog.setContentView(view)

        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val textViewStatus = view.findViewById<TextView>(R.id.textViewStatus)
        val buttonCancel =
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonCancel)
        var isCancelled = false

        buttonCancel.setOnClickListener { isCancelled = true; dialog.dismiss() }

        val sourcesUrl =
            "https://raw.githubusercontent.com/LowSkillDeveloper/WIFI-Frankenstein/refs/heads/service/recommended-databases.json"

        lifecycleScope.launch {
            try {
                val sources = dbSetupViewModel.fetchSources(sourcesUrl)
                    ?: emptyList<com.lsd.wififrankenstein.ui.dbsetup.DbSource>()
                if (sources.isEmpty()) {
                    textViewStatus.text = getString(R.string.no_recommended_sources_skip)
                    progressBar.visibility = View.GONE
                    return@launch
                }

                textViewStatus.text = getString(R.string.loading_recommended_databases)
                val allDatabases = mutableListOf<SmartLinkDbInfo>()
                for (source in sources) {
                    if (isCancelled) return@launch
                    val databases = dbSetupViewModel.fetchSmartLinkDatabases(source.smartlinkUrl)
                    if (databases != null) {
                        allDatabases.addAll(databases)
                    }
                }

                dialog.dismiss()

                if (allDatabases.isNotEmpty()) {
                    showMultiSelectDialog(allDatabases.distinctBy { it.id })
                } else {
                    showError(getString(R.string.db_step1_no_databases))
                }
            } catch (e: Exception) {
                if (!isCancelled) {
                    textViewStatus.text = getString(R.string.db_error_loading)
                    progressBar.visibility = View.GONE
                }
            }
        }

        dialog.show()
    }

    private fun showMultiSelectDialog(databases: List<SmartLinkDbInfo>) {
        val checkedItems = BooleanArray(databases.size) { false }
        val dialogView = layoutInflater.inflate(R.layout.dialog_database_select, null)
        val recyclerView =
            dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerViewDatabases)
        val selectionCount =
            dialogView.findViewById<TextView>(R.id.textViewSelectionCount)

        fun updateSelectionCount() {
            val count = checkedItems.count { it }
            selectionCount.text = if (count == 0) {
                getString(R.string.select_databases_to_download)
            } else {
                getString(R.string.x_databases_selected, count)
            }
        }

        val adapter = DatabaseSelectAdapter(databases, checkedItems) { updateSelectionCount() }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        updateSelectionCount()

        MaterialAlertDialogBuilder(requireContext())
            .setCustomTitle(null)
            .setView(dialogView)
            .setPositiveButton(R.string.download) { _, _ ->
                val selected = databases.filterIndexed { i, _ -> checkedItems[i] }
                if (selected.isNotEmpty()) showDownloadProgressDialog(selected)
                else showError(getString(R.string.no_databases_selected))
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
        dialog.setOnDismissListener { dialogJob.cancel() }
        dialog.show()

        val progressText = dialog.findViewById<TextView>(R.id.textViewProgress)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.progressBarDownload)
        val cancelButton = dialog.findViewById<Button>(R.id.buttonCancel)
        var isCancelled = false
        cancelButton?.setOnClickListener {
            isCancelled = true; dialogJob.cancel(); dialog.dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch(dialogJob) {
            try {
                databases.forEachIndexed { index, dbInfo ->
                    if (!isActive) return@forEachIndexed
                    progressText?.text = getString(
                        R.string.downloading_database_progress,
                        index + 1,
                        databases.size,
                        dbInfo.name
                    )
                    progressBar?.progress = 0

                    val dbItem =
                        dbSetupViewModel.downloadSmartLinkDatabase(dbInfo) { progress, _, _ ->
                            progressBar?.progress = progress
                        }
                    dbItem?.let { item ->
                        if (item.dbType == DbType.SQLITE_FILE_CUSTOM || item.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM) {
                            withContext(Dispatchers.Main) {
                                dialog.dismiss()
                                dbSetupViewModel.initializeSQLiteCustomHelper(
                                    item.path.toUri(),
                                    item.directPath
                                )
                                val tableNames = dbSetupViewModel.getCustomTableNames()
                                if (tableNames != null && tableNames.isNotEmpty()) {
                                    showCustomDbSetupDialog(item, tableNames)
                                } else {
                                    showSnackbar(getString(R.string.error_reading_database))
                                }
                            }
                        } else {
                            val existing = dbSetupViewModel.dbList.value?.find { it.id == item.id }
                            if (existing == null) {
                                dbSetupViewModel.addDb(item)
                                welcomeViewModel.addSelectedDatabase(item)
                            }
                            withContext(Dispatchers.Main) {
                                refreshDbList()
                                showSuccess(getString(R.string.db_added_successfully))
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) dialog.show()
                    dialog.dismiss()
                    if (!isCancelled) {
                        refreshDbList()
                        showSuccess(getString(R.string.download_completed))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    if (e !is CancellationException) {
                        showSnackbar(getString(R.string.error_downloading_database, e.message))
                        refreshDbList()
                    }
                }
            }
        }
    }


    private fun showCustomDbSetupDialog(dbItem: DbItem, tableNames: List<String>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.select_table))
            .setItems(tableNames.toTypedArray()) { _, which ->
                val table = tableNames[which]
                dbSetupViewModel.setSelectedTable(table)
                showColumnMappingDialog(dbItem, table)
            }
            .show()
    }

    private fun showColumnMappingDialog(dbItem: DbItem, tableName: String) {
        val columnNames = dbSetupViewModel.getCustomColumnNames(tableName) ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_column_mapping, null)

        val columnKeys = listOf(
            "essid", "mac", "wifi_pass", "wps_pin", "admin_panel",
            "admin_login", "admin_pass", "latitude", "longitude",
            "security_type", "timestamp"
        )

        val spinners = listOf(
            dialogView.findViewById<Spinner>(R.id.spinnerEssid),
            dialogView.findViewById<Spinner>(R.id.spinnerMac),
            dialogView.findViewById<Spinner>(R.id.spinnerWifiPass),
            dialogView.findViewById<Spinner>(R.id.spinnerWpsPin),
            dialogView.findViewById<Spinner>(R.id.spinnerAdminPanel),
            dialogView.findViewById<Spinner>(R.id.spinnerAdminLogin),
            dialogView.findViewById<Spinner>(R.id.spinnerAdminPass),
            dialogView.findViewById<Spinner>(R.id.spinnerLatitude),
            dialogView.findViewById<Spinner>(R.id.spinnerLongitude),
            dialogView.findViewById<Spinner>(R.id.spinnerSecurityType),
            dialogView.findViewById<Spinner>(R.id.spinnerTimestamp)
        )

        val colNames = listOf(getString(R.string.not_specified)) + columnNames
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, colNames).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        spinners.forEach { it.adapter = adapter; it.setSelection(0) }

        val btnFields = dialogView.findViewById<Button>(R.id.buttonShowAdditionalFields)
        val layoutFields = dialogView.findViewById<LinearLayout>(R.id.layoutAdditionalFields)
        btnFields.setOnClickListener {
            layoutFields.visibility = if (layoutFields.isVisible) View.GONE else View.VISIBLE
            btnFields.text =
                if (layoutFields.isVisible) getString(R.string.hide_additional_fields) else getString(
                    R.string.show_additional_fields
                )
        }

        val checkAdminSplit =
            dialogView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkAdminSplit)
        val layoutAdminSplit = dialogView.findViewById<LinearLayout>(R.id.layoutAdminSplit)
        checkAdminSplit.setOnCheckedChangeListener { _, isChecked ->
            spinners[4].visibility = if (isChecked) View.GONE else View.VISIBLE
            layoutAdminSplit.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                spinners[4].setSelection(0)
            } else {
                spinners[5].setSelection(0)
                spinners[6].setSelection(0)
            }
        }

        val btnAutoMap = dialogView.findViewById<Button>(R.id.buttonAutoMap)
        btnAutoMap.setOnClickListener {
            btnAutoMap.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ColumnAutoMapper.autoMap(columnNames) { col ->
                        ColumnAutoMapper.ColumnStats(
                            values = dbSetupViewModel.getCustomSampleValues(tableName, col)
                                .orEmpty(),
                            fillRatio = dbSetupViewModel.getCustomFillRatio(tableName, col)
                        )
                    }
                }
                btnAutoMap.isEnabled = true
                if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.STARTED
                    )
                ) {
                    applyAutoMap(result, checkAdminSplit, columnKeys, spinners, colNames)
                    showSuccess(
                        if (result.map.isNotEmpty()) {
                            getString(R.string.auto_mapping_applied)
                        } else {
                            getString(R.string.auto_mapping_failed)
                        }
                    )
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.map_columns))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                val splitMode = checkAdminSplit.isChecked
                val columnMap = columnKeys
                    .mapIndexedNotNull { index, key ->
                        val value = spinners[index].selectedItem.toString()
                        if (value == getString(R.string.not_specified)) null else key to value
                    }
                    .toMap()
                    .toMutableMap()
                    .apply {
                        if (splitMode) {
                            remove("admin_panel")
                        } else {
                            remove("admin_login")
                            remove("admin_pass")
                        }
                    }

                val finalItem = dbItem.copy(
                    tableName = tableName,
                    columnMap = columnMap
                )
                dbSetupViewModel.addDb(finalItem)
                welcomeViewModel.addSelectedDatabase(finalItem)
                refreshDbList()
                showSuccess(getString(R.string.db_added_successfully))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun applyAutoMap(
        result: ColumnAutoMapper.AutoMapResult,
        checkAdminSplit: com.google.android.material.checkbox.MaterialCheckBox,
        columnKeys: List<String>,
        spinners: List<Spinner>,
        colNames: List<String>
    ) {
        when (result.adminMode) {
            ColumnAutoMapper.AdminMode.COMBINED -> {
                checkAdminSplit.isChecked = false
                setSpinnerSelection(spinners[4], result.map["admin_panel"], colNames)
            }

            ColumnAutoMapper.AdminMode.SPLIT -> {
                checkAdminSplit.isChecked = true
                setSpinnerSelection(spinners[5], result.map["admin_login"], colNames)
                setSpinnerSelection(spinners[6], result.map["admin_pass"], colNames)
            }

            ColumnAutoMapper.AdminMode.NONE -> {
                checkAdminSplit.isChecked = false
            }
        }
        result.map.forEach { (key, column) ->
            if (key.startsWith("admin_")) return@forEach
            val idx = columnKeys.indexOf(key)
            if (idx >= 0) setSpinnerSelection(spinners[idx], column, colNames)
        }
    }

    private fun setSpinnerSelection(spinner: Spinner, column: String?, colNames: List<String>) {
        if (column.isNullOrBlank()) return
        val pos = colNames.indexOf(column)
        if (pos > 0) spinner.setSelection(pos)
    }

    private fun getFileSizeInMB(uri: Uri): Float {
        return try {
            val cursor = context?.contentResolver?.query(uri, null, null, null, null)
            val sizeIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.SIZE)
            cursor?.moveToFirst()
            val size = if (sizeIndex != null && cursor != null) cursor.getLong(sizeIndex) else 0L
            cursor?.close()
            if (size > 0) size / (1024f * 1024f) else 0f
        } catch (_: Exception) {
            0f
        }
    }

    private fun createDbItemFromSmartLinkInfo(info: SmartLinkDbInfo): DbItem {
        val dbType =
            if (info.type == "3wifi") DbType.SMARTLINK_SQLITE_FILE_P3WIFI else DbType.SMARTLINK_SQLITE_FILE_CUSTOM
        return DbItem(
            id = UUID.randomUUID().toString(),
            path = "",
            directPath = null,
            type = if (info.type == "3wifi") getString(R.string.db_type_3wifi) else getString(R.string.db_type_sqlite_custom),
            dbType = dbType,
            originalSizeInMB = 0f,
            cachedSizeInMB = 0f,
            idJson = info.id,
            smartlinkType = info.type,
            tableName = info.tableName,
            columnMap = info.columnMapping
        )
    }

    private fun setupWarningObserver() {
        dbSetupViewModel.oldFormatWarning.observe(viewLifecycleOwner) { warning ->
            if (warning != null && _binding != null) {
                showSnackbar(warning)
            }
        }
    }

    private fun showError(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun showSuccess(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun showSnackbar(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = WelcomeDatabasesFragment()
    }
}
