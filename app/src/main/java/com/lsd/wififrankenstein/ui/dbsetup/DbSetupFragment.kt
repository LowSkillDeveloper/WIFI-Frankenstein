package com.lsd.wififrankenstein.ui.dbsetup

import android.app.Activity
import android.content.Context
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentDbSetupBinding
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork
import com.lsd.wififrankenstein.ui.welcome.DatabaseSelectAdapter
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.SslHelper
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class DbSetupFragment : Fragment() {

    private var _binding: FragmentDbSetupBinding? = null
    private val binding get() = _binding!!
    private val viewModel by viewModels<DbSetupViewModel>()
    private lateinit var dbListAdapter: DbListAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var isBackupBeforeClear = false

    private val selectFileLauncher = registerForActivityResult(
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
        _binding = FragmentDbSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupCardClicks()
        setupRecyclerView()
        observeViewModel()
        setupLocalDbCard()
        updateLocalDbStats()
        setupAdvancedOptions()
    }

    private fun setupCardClicks() {
        binding.cardDownload.setOnClickListener { showRecommendedBottomSheet() }
        binding.cardAddFile.setOnClickListener { pickFile() }
        binding.cardAddUrl.setOnClickListener { showUrlInputBottomSheet() }
        binding.cardAddApiServer.setOnClickListener { showApiConfigBottomSheet() }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        selectFileLauncher.launch(intent)
    }

    private fun handleSelectedFile(uri: Uri) {
        try {
            context?.contentResolver?.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            autoDetectAndAdd(uri)
        } catch (e: Exception) {
            showSnackbar(getString(R.string.error_opening_file))
        }
    }

    private fun autoDetectAndAdd(uri: Uri) {
        val directPath = viewModel.getDirectPathFromUri(uri)
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

        job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = viewModel.initializeSQLite3WiFiHelperWithProgress(
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
                    val tableNames = viewModel.getTableNames() ?: emptyList()
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
                            viewModel.addDb(dbItem)
                            showSnackbar(getString(R.string.db_added_successfully))
                        }
                    } else {
                        withContext(Dispatchers.Main) { dialog.dismiss() }
                        processFileAsCustom(uri, directPath)
                    }
                } else if (!isCancelled) {
                    withContext(Dispatchers.Main) { dialog.dismiss() }
                    processFileAsCustom(uri, directPath)
                }
            } catch (e: CancellationException) {

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) dialog.dismiss()
                }
                processFileAsCustom(uri, directPath)
            } finally {
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) dialog.dismiss()
                }
            }
        }
    }

    private fun processFileAsCustom(uri: Uri, directPath: String?) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            try {
                viewModel.initializeSQLiteCustomHelper(uri, directPath)
                val tableNames = viewModel.getCustomTableNames()
                if (tableNames != null && tableNames.isNotEmpty()) {
                    val dbItem = DbItem(
                        id = UUID.randomUUID().toString(),
                        path = uri.toString(),
                        directPath = directPath,
                        type = getString(R.string.db_type_sqlite_custom),
                        dbType = DbType.SQLITE_FILE_CUSTOM,
                        originalSizeInMB = getFileSizeInMB(uri),
                        cachedSizeInMB = 0f
                    )
                    showCustomDbSetupDialog(dbItem, tableNames)
                } else {
                    showSnackbar(getString(R.string.invalid_3wifi_sqlite_structure))
                }
            } catch (e: Exception) {
                showSnackbar(e.message ?: getString(R.string.error_opening_file))
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
                showSnackbar(getString(R.string.db_invalid_url))
                return@setOnClickListener
            }
            dialog.dismiss()
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val databases = viewModel.fetchSmartLinkDatabases(url)
                    if (databases != null && databases.isNotEmpty()) {
                        showMultiSelectDialog(databases)
                    } else {
                        showSnackbar(getString(R.string.db_step1_no_databases))
                    }
                } catch (e: Exception) {
                    showSnackbar(getString(R.string.db_error_loading))
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
                        textInputApiUrl.endIconDrawable =
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                requireContext(),
                                R.drawable.ic_web
                            )
                    }

                    url.startsWith("http://") || url.startsWith("https://") -> {
                        textInputApiUrl.endIconDrawable =
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                requireContext(),
                                R.drawable.ic_check
                            )
                    }

                    else -> {
                        textInputApiUrl.endIconDrawable =
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                requireContext(),
                                R.drawable.ic_close
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
        autoComplete.setText(authMethods[2], false)
        textInputReadKey.visibility = View.GONE
        textInputWriteKey.visibility = View.GONE

        buttonAdd.setOnClickListener {
            val url = editTextApiUrl.text.toString()
            if (url.isNotEmpty()) {
                val authMethodText = autoComplete.text.toString()
                val authMethod = when (authMethodText) {
                    getString(R.string.auth_method_api_keys) -> AuthMethod.API_KEYS
                    getString(R.string.auth_method_login_password) -> AuthMethod.LOGIN_PASSWORD
                    else -> AuthMethod.NO_AUTH
                }

                buttonAdd.isEnabled = false
                buttonAdd.text = getString(R.string.detecting_server)

                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val apiProtocol = detectServerProtocol(url)
                        Log.d("DbSetupFragment", "Detected protocol: $apiProtocol for $url")

                        when {
                            apiProtocol == "3wifi_app" && authMethod == AuthMethod.LOGIN_PASSWORD -> {
                                val login = textInputLogin.editText?.text.toString()
                                val password = textInputPassword.editText?.text.toString()
                                if (login.isNotBlank() && password.isNotBlank()) {
                                    val helper = ThreeWifiAppMapHelper(requireContext(), url)
                                    val loginSuccess = withContext(Dispatchers.IO) {
                                        helper.login(login, password)
                                    }
                                    if (loginSuccess) {
                                        val jwtToken = helper.getJwtToken()
                                        val dbItem =
                                            create3wifiAppDbItem(url, jwtToken, login, password)
                                        viewModel.addDb(dbItem)
                                        delay(100)
                                        dialog.dismiss()
                                        showSnackbar(getString(R.string.login_successful))
                                    } else {
                                        showSnackbar(getString(R.string.login_failed))
                                    }
                                } else {
                                    val dbItem = create3wifiAppDbItem(url, null, null, null)
                                    viewModel.addDb(dbItem)
                                    delay(100)
                                    dialog.dismiss()
                                    showSnackbar(getString(R.string.db_added_successfully))
                                }
                            }

                            apiProtocol == "3wifi_app" -> {
                                val dbItem = create3wifiAppDbItem(url, null, null, null)
                                viewModel.addDb(dbItem)
                                delay(100)
                                dialog.dismiss()
                                showSnackbar(getString(R.string.db_added_successfully))
                            }

                            authMethod == AuthMethod.API_KEYS -> {
                                val readKey =
                                    textInputReadKey.editText?.text.toString()
                                        .takeIf { it.isNotBlank() }
                                        ?: "000000000000"
                                val writeKey =
                                    textInputWriteKey.editText?.text.toString()
                                        .takeIf { it.isNotBlank() }
                                        ?: "000000000000"
                                val dbItem = ApiServerHelper.createDbItemWithKeys(
                                    url,
                                    readKey,
                                    writeKey,
                                    authMethod,
                                    getString(R.string.db_type_3wifi)
                                )
                                viewModel.addDb(dbItem)
                                delay(100)
                                dialog.dismiss()
                                showSnackbar(getString(R.string.db_added_successfully))
                            }

                            authMethod == AuthMethod.LOGIN_PASSWORD -> {
                                val login = textInputLogin.editText?.text.toString()
                                val password = textInputPassword.editText?.text.toString()
                                if (login.isNotBlank() && password.isNotBlank()) {
                                    val progressDialog =
                                        MaterialAlertDialogBuilder(requireContext())
                                            .setView(R.layout.dialog_test_progress)
                                            .setCancelable(false)
                                            .create()
                                    progressDialog.show()

                                    try {
                                        val (readKey, writeKey, userInfo) = ApiServerHelper.getApiKeysFromLogin(
                                            serverUrl = url,
                                            login = login,
                                            password = password
                                        ) { error ->
                                            UserManager(requireContext()).getErrorDesc(error)
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
                                            viewModel.addDb(dbItem)
                                            delay(100)
                                            dialog.dismiss()
                                            showSnackbar(getString(R.string.login_successful))
                                        } else {
                                            showSnackbar(getString(R.string.login_failed))
                                        }
                                    } catch (e: Exception) {
                                        progressDialog.dismiss()
                                        showSnackbar(e.message ?: getString(R.string.login_failed))
                                    }
                                } else {
                                    val dbItem = ApiServerHelper.createDbItemWithKeys(
                                        url,
                                        "000000000000",
                                        "000000000000",
                                        AuthMethod.NO_AUTH,
                                        getString(R.string.db_type_3wifi)
                                    )
                                    viewModel.addDb(dbItem)
                                    delay(100)
                                    dialog.dismiss()
                                    showSnackbar(getString(R.string.db_added_successfully))
                                }
                            }

                            else -> {
                                val dbItem = ApiServerHelper.createDbItemWithKeys(
                                    url,
                                    "000000000000",
                                    "000000000000",
                                    authMethod,
                                    getString(R.string.db_type_3wifi)
                                )
                                viewModel.addDb(dbItem)
                                delay(100)
                                dialog.dismiss()
                                showSnackbar(getString(R.string.db_added_successfully))
                            }
                        }
                    } catch (e: Exception) {
                        showSnackbar(e.message ?: getString(R.string.operation_failed))
                    } finally {
                        buttonAdd.isEnabled = true
                        buttonAdd.text = getString(R.string.add)
                    }
                }
            } else {
                showSnackbar(getString(R.string.db_invalid_url))
            }
        }

        dialog.show()
    }

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

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val sources = viewModel.fetchSources(sourcesUrl) ?: emptyList()
                if (sources.isEmpty()) {
                    textViewStatus.text = getString(R.string.no_recommended_sources_skip)
                    progressBar.visibility = View.GONE
                    return@launch
                }

                textViewStatus.text = getString(R.string.loading_recommended_databases)
                val allDatabases = mutableListOf<SmartLinkDbInfo>()
                for (source in sources) {
                    if (isCancelled) return@launch
                    val databases = viewModel.fetchSmartLinkDatabases(source.smartlinkUrl)
                    if (databases != null) {
                        allDatabases.addAll(databases)
                    }
                }

                dialog.dismiss()

                if (allDatabases.isNotEmpty()) {
                    showMultiSelectDialog(
                        allDatabases.distinctBy { it.id },
                        sources.mapNotNull { it.description }.distinct().joinToString("\n")
                    )
                } else {
                    showSnackbar(getString(R.string.db_step1_no_databases))
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

    private fun showMultiSelectDialog(
        databases: List<SmartLinkDbInfo>,
        description: String? = null
    ) {
        val checkedItems = BooleanArray(databases.size) { false }
        val dialogView = layoutInflater.inflate(R.layout.dialog_database_select, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewDatabases)
        val selectionCount = dialogView.findViewById<TextView>(R.id.textViewSelectionCount)
        val sourceDescription = dialogView.findViewById<TextView>(R.id.textViewSourceDescription)
        if (!description.isNullOrBlank()) {
            sourceDescription.text = description
            sourceDescription.visibility = View.VISIBLE
        }

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
                else showSnackbar(getString(R.string.no_databases_selected))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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
        val failuresText = dialog.findViewById<TextView>(R.id.textViewFailures)
        val failures = mutableListOf<Pair<String, String>>()
        var isCancelled = false
        cancelButton?.setOnClickListener {
            isCancelled = true; dialogJob.cancel(); dialog.dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch(dialogJob) {
            try {
                databases.forEachIndexed { index, dbInfo ->
                    if (isCancelled) return@forEachIndexed
                    progressText?.text = getString(
                        R.string.downloading_database_progress,
                        index + 1,
                        databases.size,
                        dbInfo.name
                    )
                    progressBar?.isIndeterminate = false
                    progressBar?.progress = 0
                    var lastShownProgress = -1
                    var lastShownExtract = -1
                    var extractingTextSet = false

                    val result = viewModel.downloadSmartLinkDatabase(dbInfo) { progress, bytes, total ->
                        when (progress) {
                            PROGRESS_EXTRACT -> {
                                val pct = bytes.toInt().coerceIn(0, 100)
                                if (!extractingTextSet) {
                                    extractingTextSet = true
                                    progressText?.text = getString(
                                        R.string.extracting_database_progress,
                                        index + 1,
                                        databases.size,
                                        dbInfo.name
                                    )
                                }
                                val indeterminate = total == null || total <= 0
                                if (progressBar?.isIndeterminate != indeterminate) {
                                    progressBar?.isIndeterminate = indeterminate
                                }
                                if (!indeterminate && pct != lastShownExtract) {
                                    lastShownExtract = pct
                                    progressBar?.progress = pct
                                }
                            }

                            else -> {
                                progressBar?.isIndeterminate = false
                                if (progress >= 0 && progress != lastShownProgress) {
                                    lastShownProgress = progress
                                    progressBar?.progress = progress
                                }
                            }
                        }
                    }
                    val item = result.dbItem
                    if (item != null) {
                        if (item.dbType == DbType.SQLITE_FILE_CUSTOM || item.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM) {
                            withContext(Dispatchers.Main) {
                                dialog.dismiss()
                                viewModel.initializeSQLiteCustomHelper(
                                    item.path.toUri(),
                                    item.directPath
                                )
                                val tableNames = viewModel.getCustomTableNames()
                                if (tableNames != null && tableNames.isNotEmpty()) {
                                    showCustomDbSetupDialog(item, tableNames)
                                } else {
                                    showSnackbar(getString(R.string.error_reading_database))
                                }
                            }
                        } else {
                            val existing = viewModel.dbList.value?.find { it.id == item.id }
                            if (existing == null) {
                                viewModel.addDb(item)
                            }
                            withContext(Dispatchers.Main) {
                                showSnackbar(getString(R.string.db_added_successfully))
                            }
                        }
                    } else {
                        val reason = result.error ?: getString(R.string.operation_failed)
                        failures.add(dbInfo.name to reason)
                        failuresText?.let { tv ->
                            tv.visibility = View.VISIBLE
                            tv.append(getString(R.string.download_failed_item, dbInfo.name, reason) + "\n")
                        }
                        progressText?.text =
                            getString(R.string.download_failed_count, failures.size, databases.size)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (failures.isNotEmpty()) {
                        progressBar?.visibility = View.GONE
                        progressText?.text = getString(R.string.download_completed_with_errors)
                        cancelButton?.text = getString(R.string.close)
                        cancelButton?.setOnClickListener { dialog.dismiss() }
                        val toastMessage = failures.joinToString("\n") { (name, reason) ->
                            getString(R.string.download_failed_item, name, reason)
                        }
                        Toast.makeText(
                            requireContext(),
                            if (toastMessage.length > 200) toastMessage.take(200) + "…" else toastMessage,
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        if (!dialog.isShowing) dialog.show()
                        dialog.dismiss()
                        if (!isCancelled) {
                            showSnackbar(getString(R.string.download_completed))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    if (e !is CancellationException) {
                        showSnackbar(getString(R.string.error_downloading_database, e.message))
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
                viewModel.setSelectedTable(table)
                showColumnMappingDialog(dbItem, table)
            }
            .show()
    }

    private fun showColumnMappingDialog(dbItem: DbItem, tableName: String) {
        val columnNames = viewModel.getCustomColumnNames(tableName) ?: return
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
                            values = viewModel.getCustomSampleValues(tableName, col).orEmpty(),
                            fillRatio = viewModel.getCustomFillRatio(tableName, col)
                        )
                    }
                }
                btnAutoMap.isEnabled = true
                if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(
                        androidx.lifecycle.Lifecycle.State.STARTED
                    )
                ) {
                    applyAutoMap(result, checkAdminSplit, columnKeys, spinners, colNames)
                    showSnackbar(
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
                viewModel.addDb(finalItem)
                showSnackbar(getString(R.string.db_added_successfully))
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

    private fun setupLocalDbCard() {
        val helper = LocalAppDbHelper(requireContext().applicationContext)

        binding.buttonInAppDatabase.setOnClickListener {
            findNavController().navigate(R.id.action_nav_db_setup_to_inAppDatabaseFragment)
        }

        binding.switchEnableIndexing.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        if (!helper.hasIndexes()) {
                            helper.enableIndexing("BASIC")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showSnackbar(getString(R.string.ds_failed_enable_indexing, e.message))
                        }
                    }
                }
            } else {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        helper.disableIndexing()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showSnackbar(getString(R.string.ds_failed_disable_indexing, e.message))
                        }
                    }
                }
            }
        }

        binding.buttonClearLocalDb.setOnClickListener {
            showClearDatabaseWarning()
        }

        binding.buttonOptimizeDb.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                LocalAppDbHelper(requireContext().applicationContext).optimizeDatabase()
                withContext(Dispatchers.Main) {
                    showSnackbar(getString(R.string.database_optimized))
                }
            }
        }

        binding.buttonRemoveDuplicates.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                LocalAppDbHelper(requireContext().applicationContext).removeDuplicates()
                withContext(Dispatchers.Main) {
                    showSnackbar(getString(R.string.duplicates_removed))
                }
            }
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

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val helper2 = LocalAppDbHelper(requireContext().applicationContext)
                val hasIndexes = helper2.hasIndexes()
                withContext(Dispatchers.Main) {
                    binding.switchEnableIndexing.isChecked = hasIndexes
                }
            } catch (e: Exception) {
            }
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

    private fun clearLocalDatabase() {
        val helper = LocalAppDbHelper(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            helper.clearDatabase()
            withContext(Dispatchers.Main) {
                updateLocalDbStats()
                showSnackbar(getString(R.string.database_cleared))
            }
        }
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

    private fun exportToJson(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val records = LocalAppDbHelper(requireContext().applicationContext).getAllRecords()
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
                val records = LocalAppDbHelper(requireContext().applicationContext).getAllRecords()
                context?.contentResolver?.openOutputStream(uri)?.use { outputStream ->
                    CSVWriter(OutputStreamWriter(outputStream)).use { writer ->
                        writer.writeNext(
                            arrayOf(
                                "ID",
                                "WiFi Name",
                                "MAC Address",
                                "Password",
                                "WPS PIN",
                                "Admin Panel",
                                "Latitude",
                                "Longitude"
                            )
                        )
                        records.forEach { record ->
                            writer.writeNext(
                                arrayOf(
                                    record.id.toString(),
                                    record.wifiName,
                                    record.macAddress,
                                    record.wifiPassword ?: "",
                                    record.wpsCode ?: "",
                                    record.adminPanel ?: "",
                                    record.latitude?.toString() ?: "",
                                    record.longitude?.toString() ?: ""
                                )
                            )
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
                        LocalAppDbHelper(requireContext().applicationContext).importRecords(records)
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
                        LocalAppDbHelper(requireContext().applicationContext).importRecords(records)
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
                    progressText?.text = getString(R.string.ds_analyzing_file)
                    progressBar?.progress = 2
                }

                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.lineSequence().forEach { _ -> totalLines++ }
                }

                withContext(Dispatchers.Main) {
                    progressText?.text = getString(R.string.ds_parsing_lines, totalLines)
                    progressBar?.progress = 5
                }

                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val allLines = reader.readLines()
                    allLines.forEach { line ->
                        parseRouterScanLine(line, importType)?.let { network ->
                            networksToAdd.add(network)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar?.progress = 15
                    progressText?.text = getString(R.string.ds_found_records_for_import, networksToAdd.size)
                }

                val helper3 = LocalAppDbHelper(requireContext().applicationContext)
                var stats = try {
                    helper3.importRecordsWithStats(networksToAdd, importType)
                } catch (e: Exception) {
                    Log.e("RouterScanImport", "Import failed", e)
                    LocalAppDbHelper.ImportStats(0, 0, 0)
                }

                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null) {
                        progressDialog.dismiss()
                        updateLocalDbStats()

                        val message = when (importType) {
                            "append_check_duplicates" -> getString(
                                R.string.import_stats,
                                stats.totalProcessed, stats.inserted, stats.duplicates
                            )

                            "replace" -> getString(R.string.database_replaced_imported, stats.inserted)
                            else -> getString(R.string.import_completed_added, stats.inserted)
                        }
                        showSnackbar(message)
                    }
                }

            } catch (e: Exception) {
                Log.e("RouterScanImport", "Import error", e)
                withContext(Dispatchers.Main) {
                    if (isAdded && _binding != null) {
                        progressDialog.dismiss()
                        showSnackbar(getString(R.string.import_error, e.message))
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

                                if (latStr.matches(LAT_REGEX) &&
                                    lonStr.matches(LON_REGEX)
                                ) {
                                    val lat = latStr.toDoubleOrNull()
                                    val lon = lonStr.toDoubleOrNull()

                                    if (lat != null && lon != null &&
                                        lat >= -90.0 && lat <= 90.0 &&
                                        lon >= -180.0 && lon <= 180.0 &&
                                        lat != 0.0 && lon != 0.0
                                    ) {
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
                    val cleanWifiKey =
                        wifiKey.takeIf { it.isNotEmpty() && it != "0" && it != "-" && it.length > 1 }
                    val cleanWpsPin =
                        wpsPin.takeIf { it.isNotEmpty() && it != "0" && it != "-" && it.length >= 8 }
                    val cleanAdminPanel = adminCredentials.takeIf {
                        it.isNotEmpty() && it != ":" && it != "-" && !it.contains("0.0.0.0") && it.contains(
                            ":"
                        )
                    }

                    if (cleanBssid.isNotEmpty()) {
                        if (!cleanBssid.matches(MAC_FULL_REGEX)) {
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                REQUEST_BACKUP_DB -> {
                    data?.data?.let { uri ->
                        val shouldClear = isBackupBeforeClear
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            exportDatabase(uri)
                            if (shouldClear) {
                                clearLocalDatabase()
                            }
                        }
                        if (shouldClear) {
                            isBackupBeforeClear = false
                            showSnackbar(getString(R.string.database_backed_up_and_cleared))
                        } else {
                            showSnackbar(getString(R.string.database_backed_up))
                        }
                    }
                }

                REQUEST_RESTORE_DB -> {
                    data?.data?.let { uri ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            LocalAppDbHelper(requireContext().applicationContext).restoreDatabaseFromUri(
                                uri
                            )
                        }
                        showSnackbar(getString(R.string.database_restored))
                        reloadFragment()
                    }
                }

                REQUEST_EXPORT_JSON -> {
                    data?.data?.let { uri -> exportToJson(uri) }
                }

                REQUEST_EXPORT_CSV -> {
                    data?.data?.let { uri -> exportToCsv(uri) }
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
                    data?.data?.let { uri -> importFromRouterScan(uri) }
                }
            }
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
            Toast.makeText(context, getString(R.string.ds_exported_successfully), Toast.LENGTH_LONG)
                .show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                getString(R.string.ds_failed_export_db, e.message),
                Toast.LENGTH_LONG
            ).show()
            Log.e("DbSetupFragment", "Failed to export database", e)
        }
    }

    private fun reloadFragment() {
        findNavController().run {
            popBackStack()
            navigate(R.id.dbSetupFragment)
        }
    }

    private var dbListInitialized = false

    override fun onResume() {
        super.onResume()

        viewLifecycleOwner.lifecycleScope.launch {
            if (!dbListInitialized) {
                viewModel.loadDbList()
                updateDbSizes()
                viewModel.checkAndUpdateDatabasesWithIndexes()
                dbListInitialized = true
            } else {
                viewModel.refreshLight()
            }
            delay(200)
            dbListAdapter.notifyDataSetChanged()
        }
    }

    private fun setupRecyclerView() {
        binding.recyclerViewDbs.apply {
            layoutManager = LinearLayoutManager(context)
            dbListAdapter = DbListAdapter(
                onItemMoved = viewModel::updateDbOrder,
                onStartDrag = { viewHolder -> itemTouchHelper.startDrag(viewHolder) },
                onItemRemoved = { position ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.remove_database)
                        .setMessage(R.string.remove_database_confirm)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            viewModel.removeDb(position)
                            Toast.makeText(
                                context,
                                R.string.database_removed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                },
                onManageIndexes = { dbItem ->
                    handleIndexManagement(dbItem)
                },
                onShowDetails = { dbItem ->
                    showDatabaseDetailsDialog(dbItem)
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

    private fun showDatabaseDetailsDialog(dbItem: DbItem) {
        val details = StringBuilder().apply {
            append(getString(R.string.ds_detail_type, dbItem.type) + "\n")
            append(getString(R.string.ds_detail_path, dbItem.path) + "\n")
            append(
                getString(
                    R.string.ds_detail_direct,
                    dbItem.directPath ?: getString(R.string.not_available)
                ) + "\n"
            )
            append(getString(R.string.ds_detail_original, dbItem.originalSizeInMB) + "\n")
            append(getString(R.string.ds_detail_cached, dbItem.cachedSizeInMB) + "\n")
            append(getString(R.string.ds_detail_index_level, dbItem.indexLevel) + "\n")
            if (!dbItem.tableName.isNullOrBlank()) {
                append(getString(R.string.ds_detail_table, dbItem.tableName) + "\n")
            }
        }.toString()

        val isCustom = dbItem.dbType == DbType.SQLITE_FILE_CUSTOM ||
                dbItem.dbType == DbType.SMARTLINK_SQLITE_FILE_CUSTOM

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(dbItem.type)
            .setMessage(details)
            .setPositiveButton(R.string.ok, null)
            .apply {
                if (isCustom) {
                    setNeutralButton(R.string.add_another_table) { _, _ ->
                        addAnotherTableFromDatabase(dbItem)
                    }
                }
            }
            .show()
    }

    private fun addAnotherTableFromDatabase(dbItem: DbItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val tableNames = withContext(Dispatchers.IO) {
                viewModel.initializeSQLiteCustomHelper(dbItem.path.toUri(), dbItem.directPath)
                viewModel.getCustomTableNames()
            }
            if (tableNames != null && tableNames.isNotEmpty()) {
                showCustomDbSetupDialog(dbItem.copy(tableName = null, columnMap = null), tableNames)
            } else {
                showSnackbar(getString(R.string.error_reading_database))
            }
        }
    }

    private fun handleIndexManagement(dbItem: DbItem) {
        if (dbItem.indexLevel == DbIndexLevel.FULL) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_indexes)
                .setMessage(R.string.delete_indexes_confirm)
                .setPositiveButton(R.string.yes) { _, _ ->
                    if (viewModel.deleteDbIndexes(dbItem)) {
                        Toast.makeText(context, R.string.indexes_deleted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, R.string.operation_failed, Toast.LENGTH_SHORT)
                            .show()
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
                    Toast.makeText(context, R.string.db_added_without_indexes, Toast.LENGTH_SHORT)
                        .show()
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
                            Toast.makeText(context, R.string.indexing_complete, Toast.LENGTH_SHORT)
                                .show()
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

    private fun observeViewModel() {
        viewModel.dbList.observe(viewLifecycleOwner) { dbList ->
            dbListAdapter.submitList(dbList)
        }

        viewModel.errorEvent.observe(viewLifecycleOwner) { errorMessage ->
            when (errorMessage) {
                "missing_file_removed" -> {
                    Toast.makeText(
                        context,
                        getString(R.string.database_file_not_found_removed),
                        Toast.LENGTH_LONG
                    ).show()
                }

                else -> {
                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewModel.oldFormatWarning.observe(viewLifecycleOwner) { warning ->
            if (warning != null) {
                showSnackbar(warning)
            }
        }

        viewModel.expressionIndexConflictEvent.observe(viewLifecycleOwner) { file ->
            if (file != null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.expression_index_dialog_title)
                    .setMessage(R.string.expression_index_dialog_message)
                    .setPositiveButton(R.string.expression_index_dialog_patch) { _, _ ->
                        viewModel.resolveExpressionIndexConflict(true)
                    }
                    .setNegativeButton(R.string.expression_index_dialog_cancel) { _, _ ->
                        viewModel.resolveExpressionIndexConflict(false)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private suspend fun detectServerProtocol(rawUrl: String): String? {
        var url = rawUrl
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        url = url.trimEnd('/')

        return withContext(Dispatchers.IO) {
            try {
                val appHelper = ThreeWifiAppMapHelper(requireContext(), url)
                if (appHelper.checkMapSupport()) {
                    return@withContext "3wifi_app"
                }
            } catch (_: Exception) {
            }

            try {
                val testUrl = "$url/fmap?tiles=0,0,1,1&zoom=1"
                val connection = URL(testUrl).openConnection() as HttpURLConnection
                SslHelper.configure(connection)
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                val supported = connection.responseCode == HttpURLConnection.HTTP_OK
                connection.disconnect()
                if (supported) return@withContext "3wifi_dev"
            } catch (_: Exception) {
            }

            null
        }
    }

    private fun create3wifiAppDbItem(
        url: String,
        jwtToken: String?,
        login: String?,
        password: String?
    ): DbItem {
        var serverUrl = url
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "https://$serverUrl"
        }
        serverUrl = serverUrl.trimEnd('/')

        return DbItem(
            id = UUID.randomUUID().toString(),
            path = serverUrl,
            directPath = null,
            type = "3WiFi App",
            dbType = DbType.WIFI_API,
            apiReadKey = "000000000000",
            apiWriteKey = null,
            login = login,
            password = password,
            authMethod = if (jwtToken != null) AuthMethod.LOGIN_PASSWORD else AuthMethod.NO_AUTH,
            userNick = null,
            userLevel = null,
            originalSizeInMB = 0f,
            cachedSizeInMB = 0f,
            apiProtocol = "3wifi_app",
            jwtToken = jwtToken
        )
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
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

    private fun updateDbSizes() {
        viewModel.dbList.value?.forEach { dbItem ->
            viewModel.updateDbSize(dbItem)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val REQUEST_BACKUP_DB = 3
        private const val REQUEST_RESTORE_DB = 4
        private const val REQUEST_EXPORT_JSON = 6
        private const val REQUEST_EXPORT_CSV = 7
        private const val REQUEST_IMPORT_JSON = 8
        private const val REQUEST_IMPORT_CSV = 9
        private const val REQUEST_IMPORT_ROUTERSCAN = 10
        private val LAT_REGEX = Regex("^\\d{1,2}\\.\\d+$")
        private val LON_REGEX = Regex("^\\d{1,3}\\.\\d+$")
        private val MAC_FULL_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    }
}
