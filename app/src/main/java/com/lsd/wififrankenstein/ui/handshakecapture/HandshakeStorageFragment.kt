package com.lsd.wififrankenstein.ui.handshakecapture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.BottomSheetSourcePickerBinding
import com.lsd.wififrankenstein.databinding.FragmentHandshakeStorageBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.ThreeWiFiCsvRow
import com.lsd.wififrankenstein.util.ThreeWiFiUploader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private data class SourceOption(val iconRes: Int, val title: String)

class HandshakeStorageFragment : Fragment() {

    private var _binding: FragmentHandshakeStorageBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HandshakeStorageViewModel by viewModels()
    private val dbSetupViewModel: DbSetupViewModel by viewModels()
    private var storageAdapter: HandshakeStorageAdapter? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitMultiSelect()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.checkStoragePermission()
        viewModel.loadStorage()
    }

    private val wordlistFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val item = pendingCrackItem ?: return@registerForActivityResult
            navigateToWpaCracker(item, wordlistType = 0, wordlistValue = uri.toString())
        }
    }

    private var pendingCrackItem: HandshakeItem? = null

    private var exportProgressDialog: AlertDialog? = null
    private var exportProgressText: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHandshakeStorageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        setupRecyclerView()
        setupButtons()
        setupSearch()
        setupSort()
        setupBulkActions()
        observeViewModel()
        viewModel.loadStorage()
    }

    private fun setupRecyclerView() {
        storageAdapter = HandshakeStorageAdapter(
            onVerify = { item -> viewModel.verifyStoredHandshake(item) },
            onCrack = { item -> showCrackBottomSheet(item) },
            onExportHccapx = { item -> viewModel.exportStoredHandshake(item, "hccapx") },
            onExport22000 = { item -> viewModel.exportStoredHandshake(item, "22000") },
            onShare = { item -> shareHandshake(item) },
            onDelete = { item -> showDeleteDialog(item) },
            onItemClick = { item -> showHandshakeDetails(item) },
            onSelectionChanged = { selected -> updateBulkActions(selected) },
            onMultiSelectModeChanged = { mode ->
                viewModel.setMultiSelectMode(mode)
                updateSelectButton(mode)
            },
            onCopyHash22000 = { item -> viewModel.getHashText(item) { hash -> copyHash(hash) } },
            onCopyHashPmkid = { item -> viewModel.getHashPmkidText(item) { hash -> copyHash(hash) } },
            onCopyHash16800 = { item -> viewModel.getHash16800Text(item) { hash -> copyHash(hash) } },
            onCopyPassword = { item -> copyHash(item.crackedPassword) },
            onCopySsid = { item -> copyHash(item.essid) },
            onCopyBssid = { item -> copyHash(item.bssid) },
            onUploadWpaSec = { item -> showWpaSecUploadDialog(item) },
            onUploadOhc = { item -> showOnlineHashCrackDialog(item) },
            onCheckWpaSec = { item -> viewModel.checkOnWpaSec(item) },
            onUploadTo3WiFi = { item -> uploadHandshakeTo3WiFi(listOf(item)) },
            hasChroot = ChrootCapabilities.isAvailable(requireContext())
        )
        binding.recyclerViewStorage.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = storageAdapter
        }
    }

    private fun setupButtons() {
        binding.buttonRefreshStorage.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.handshake_refresh_title)
                .setItems(
                    arrayOf(
                        getString(R.string.handshake_refresh_scan),
                        getString(R.string.handshake_refresh_reparse)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> viewModel.refreshStorage(HandshakeStorageViewModel.RefreshMode.SCAN_ONLY)
                        1 -> viewModel.refreshStorage(HandshakeStorageViewModel.RefreshMode.REPARSE_ALL)
                    }
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
        binding.buttonSelectMode.setOnClickListener {
            val current = storageAdapter?.isMultiSelectMode ?: false
            storageAdapter?.isMultiSelectMode = !current
        }
        binding.buttonSelectAll.setOnClickListener {
            storageAdapter?.selectAll()
        }
        binding.buttonImportHandshake.setOnClickListener {
            ImportHandshakeBottomSheet().show(parentFragmentManager, "ImportHandshake")
        }
        binding.buttonStartCapture.setOnClickListener {
            try {
                findNavController().navigate(R.id.nav_airodump)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.hsc_cannot_open_capture), Toast.LENGTH_SHORT).show()
            }
        }
        binding.buttonImportHandshakeEmpty.setOnClickListener {
            ImportHandshakeBottomSheet().show(parentFragmentManager, "ImportHandshake")
        }
        TooltipCompat.setTooltipText(
            binding.buttonSelectMode,
            getString(R.string.handshake_select)
        )
        TooltipCompat.setTooltipText(
            binding.buttonSelectAll,
            getString(R.string.handshake_select_all)
        )
        TooltipCompat.setTooltipText(
            binding.buttonImportHandshake,
            getString(R.string.button_import_handshake)
        )
        TooltipCompat.setTooltipText(
            binding.buttonRefreshStorage,
            getString(R.string.handshake_refresh_title)
        )
        TooltipCompat.setTooltipText(
            binding.btnBulkDelete,
            getString(R.string.handshake_bulk_delete)
        )
        TooltipCompat.setTooltipText(
            binding.btnBulkShare,
            getString(R.string.handshake_bulk_share)
        )
        TooltipCompat.setTooltipText(
            binding.btnBulkCopy22000,
            getString(R.string.handshake_copy_hash_22000)
        )
        TooltipCompat.setTooltipText(
            binding.btnBulkUpload,
            getString(R.string.handshake_bulk_upload)
        )
    }

    private fun updateSelectButton(isMultiSelect: Boolean) {
        binding.buttonSelectMode.text =
            if (isMultiSelect) getString(R.string.handshake_done) else getString(R.string.handshake_select)
        TooltipCompat.setTooltipText(
            binding.buttonSelectMode,
            getString(if (isMultiSelect) R.string.handshake_done else R.string.handshake_select)
        )
        binding.buttonImportHandshake.visibility = if (isMultiSelect) View.GONE else View.VISIBLE
        binding.buttonSelectAll.visibility = if (isMultiSelect) View.VISIBLE else View.GONE
        binding.layoutBulkActions.visibility = if (isMultiSelect) View.VISIBLE else View.GONE
        binding.cardSearch.visibility = if (isMultiSelect) View.GONE else View.VISIBLE
        binding.textStorageCount.text = if (isMultiSelect) {
            getString(R.string.handshake_select_all)
        } else {
            val count = viewModel.storageItems.value?.size ?: 0
            if (count > 0) resources.getQuantityString(
                R.plurals.handshake_count_format, count, count
            ) else ""
        }
        backCallback.isEnabled = isMultiSelect
    }

    private fun setupSearch() {
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
                binding.iconClearSearch.visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        binding.iconClearSearch.setOnClickListener {
            binding.editSearch.text.clear()
        }
    }

    private fun setupSort() {
        binding.chipSortDate.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipSortName.isChecked = false
                val current = viewModel.sortMode.value
                viewModel.setSortMode(
                    when (current) {
                        SortMode.DATE_DESC -> SortMode.DATE_ASC
                        else -> SortMode.DATE_DESC
                    }
                )
            }
        }
        binding.chipSortName.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipSortDate.isChecked = false
                val current = viewModel.sortMode.value
                viewModel.setSortMode(
                    when (current) {
                        SortMode.NAME_ASC -> SortMode.NAME_DESC
                        else -> SortMode.NAME_ASC
                    }
                )
            }
        }
        viewModel.sortMode.observe(viewLifecycleOwner) { mode ->
            val dateChecked = mode == SortMode.DATE_DESC || mode == SortMode.DATE_ASC
            val nameChecked = mode == SortMode.NAME_DESC || mode == SortMode.NAME_ASC
            binding.chipSortDate.isChecked = dateChecked
            binding.chipSortName.isChecked = nameChecked
            val dateIcon =
                if (mode == SortMode.DATE_DESC) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            binding.chipSortDate.setChipIconResource(dateIcon)
        }
    }

    private fun setupBulkActions() {
        binding.btnBulkDelete.setOnClickListener { onBulkDelete() }
        binding.btnBulkShare.setOnClickListener { onBulkShare() }
        binding.btnBulkCopy22000.setOnClickListener { onBulkCopyHash22000() }
        binding.btnBulkUpload.setOnClickListener { onBulkUpload() }
    }

    private fun updateBulkActions(selected: Set<String>) {
        val count = selected.size
        binding.textBulkCount.text =
            resources.getQuantityString(R.plurals.handshake_count_format, count, count)
        binding.layoutBulkActions.visibility = if (count > 0) View.VISIBLE else View.GONE
        val items = storageAdapter?.currentList?.filter { it.filePath in selected }.orEmpty()
        val anyFileOrHash = items.any {
            it.fileExists || it.hash22000 != null || it.hashPmkid != null || it.hash16800 != null
        }
        val anyHashOrFile = items.any { it.hash22000 != null || it.fileExists }
        val anyHash22000 = items.any { it.hash22000 != null }
        binding.btnBulkShare.isEnabled = anyFileOrHash
        binding.btnBulkShare.alpha = if (anyFileOrHash) 1f else 0.4f
        binding.btnBulkCopy22000.isEnabled = anyHashOrFile
        binding.btnBulkCopy22000.alpha = if (anyHashOrFile) 1f else 0.4f
        binding.btnBulkUpload.isEnabled = anyHash22000
        binding.btnBulkUpload.alpha = if (anyHash22000) 1f else 0.4f
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            updateContentVisibility(loading, viewModel.storageItems.value ?: emptyList())
        }

        viewModel.storageItems.observe(viewLifecycleOwner) { items ->
            storageAdapter?.submitList(items)
            updateContentVisibility(viewModel.isLoading.value ?: false, items)
            highlightItemIfRequested(items)
        }

        viewModel.storageCrackResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                val (_, password) = result
                showResultDialog(password)
                viewModel.clearStorageCrackResult()
            }
        }

        viewModel.hcxpcapngtoolResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                showHcxpcapngtoolResultDialog(result)
                viewModel.clearStorageHcxpcapngtoolResult()
            }
        }

        viewModel.wpaSecResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                val (fileName, status) = result
                val msg = when (status) {
                    "__NEED_KEY__" -> null
                    "__UPLOAD_FAILED__" -> getString(R.string.hsc_wpasec_upload_failed)
                    "password_known" -> getString(R.string.hsc_wpasec_password_known, fileName)
                    "not_found" -> getString(R.string.hsc_wpasec_password_not_found, fileName)
                    else -> getString(R.string.handshake_key_found, status)
                }
                if (msg != null && status != "__NEED_KEY__") {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(if (status.startsWith("password_")) R.string.wpasec_check else R.string.handshake_crack)
                        .setMessage(msg)
                        .setPositiveButton(R.string.close, null)
                        .show()
                }
                viewModel.clearWpaSecResult()
            }
        }

        viewModel.wpaSecCheckDone.observe(viewLifecycleOwner) { done ->
            if (done) {
                Toast.makeText(requireContext(), getString(R.string.hsc_wpasec_check_complete), Toast.LENGTH_SHORT)
                    .show()
                viewModel.clearWpaSecCheckDone()
            }
        }

        viewModel.orphanFiles.observe(viewLifecycleOwner) { orphans ->
            if (orphans != null && orphans.isNotEmpty()) {
                showOrphanFilesDialog(orphans)
            }
        }

        viewModel.orphanImportRunning.observe(viewLifecycleOwner) { running ->
            binding.progressLoading.visibility = if (running) View.VISIBLE else View.GONE
        }

        viewModel.manageStoragePermissionRequired.observe(viewLifecycleOwner) { required ->
            if (required) {
                showManageStoragePermissionDialog()
            }
        }
    }

    private fun updateContentVisibility(loading: Boolean, items: List<HandshakeItem>) {
        val empty = items.isEmpty()
        binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.loadingContainer.visibility = if (loading || empty) View.VISIBLE else View.GONE
        binding.layoutEmptyState.visibility = if (!loading && empty) View.VISIBLE else View.GONE
        binding.cardSearch.visibility = if (empty) View.GONE else View.VISIBLE
        binding.buttonSelectMode.visibility = if (empty) View.GONE else View.VISIBLE
        binding.buttonImportHandshake.visibility = if (empty) View.GONE else View.VISIBLE
        binding.recyclerViewStorage.visibility = if (empty) View.GONE else View.VISIBLE
        binding.textStorageCount.text = if (!empty) resources.getQuantityString(
            R.plurals.handshake_count_format, items.size, items.size
        ) else ""
    }

    private fun showManageStoragePermissionDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.manage_external_storage_title)
            .setMessage(R.string.manage_external_storage_message)
            .setPositiveButton(R.string.manage_external_storage_grant) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                    manageStorageLauncher.launch(intent)
                }
            }
            .setNegativeButton(R.string.close, null)
            .setCancelable(false)
            .show()
    }

    private fun showOrphanFilesDialog(orphans: List<HandshakeStorageManager.OrphanFile>) {
        val count = orphans.size
        val message = getString(R.string.handshake_orphan_message, count)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_orphan_title)
            .setMessage(message)
            .setPositiveButton(R.string.handshake_orphan_import) { _, _ ->
                viewModel.importOrphanFiles()
                Toast.makeText(
                    requireContext(),
                    R.string.handshake_orphan_importing,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.handshake_orphan_dismiss) { _, _ ->
                viewModel.dismissOrphanFiles()
            }
            .setCancelable(false)
            .show()
    }

    private fun showHcxpcapngtoolResultDialog(result: HandshakeStorageViewModel.HcxpcapngtoolResult) {
        val fields = buildString {
            appendLine(
                if (result.valid) getString(R.string.handshake_hcx_handshake_valid) else getString(
                    R.string.handshake_hcx_no_handshake
                )
            )
            appendLine()
            appendLine("${getString(R.string.handshake_verify_eapol)}: ${result.eapolCount}")
            appendLine("${getString(R.string.handshake_verify_pmkid_count)}: ${result.pmkidCount}")
            appendLine("${getString(R.string.handshake_verify_packets)}: ${result.packetsTotal}")
            appendLine("${getString(R.string.handshake_verify_duration)}: ${result.durationSec}s")
            if (result.essid.isNotBlank()) appendLine("${getString(R.string.handshake_verify_essid)}: ${result.essid}")
            if (result.channel > 0) appendLine("${getString(R.string.handshake_verify_channel)}: ${result.channel}")
        }

        val rawOutput = result.rawOutput.ifBlank { getString(R.string.handshake_hcx_no_handshake) }
        val scrollableRaw = TextView(requireContext()).apply {
            text = rawOutput
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            setLineSpacing(0f, 1.15f)
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = fields; textSize = 14f; setPadding(24, 16, 24, 8)
            })
            addView(TextView(requireContext()).apply {
                text = getString(R.string.handshake_verify_raw)
                textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(
                24,
                8,
                24,
                4
            )
            })
            addView(scrollableRaw)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_verify_hcx_title)
            .setView(content)
            .setPositiveButton(R.string.handshake_copy_raw) { _, _ ->
                copyHash(rawOutput)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showCrackBottomSheet(item: HandshakeItem) {
        val options = listOf(
            SourceOption(
                R.drawable.ic_folder_open,
                getString(R.string.handshake_crack_mode_wordlist)
            ),
            SourceOption(R.drawable.ic_key, getString(R.string.handshake_crack_mode_single)),
            SourceOption(R.drawable.ic_content_copy, getString(R.string.handshake_crack_mode_list)),
            SourceOption(
                R.drawable.ic_cloud_search,
                getString(R.string.handshake_crack_mode_wpasec)
            ),
            SourceOption(
                R.drawable.ic_open_in_browser,
                getString(R.string.handshake_crack_mode_wpacracker)
            )
        )
        showSourcePickerBottomSheet(getString(R.string.handshake_crack), options) { which ->
            when (which) {
                0 -> {
                    pendingCrackItem = item
                    wordlistFilePicker.launch(
                        arrayOf(
                            "text/plain",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                }

                1 -> showSinglePasswordDialog(item)
                2 -> showPasswordListDialog(item)
                3 -> navigateToWpaCracker(item, wordlistType = 3)
                4 -> navigateToWpaCracker(item)
            }
        }
    }

    private fun showSourcePickerBottomSheet(
        title: String,
        options: List<SourceOption>,
        onSelected: (Int) -> Unit
    ) {
        val dialog = BottomSheetDialog(requireContext())
        val binding = BottomSheetSourcePickerBinding.inflate(LayoutInflater.from(requireContext()))
        dialog.setContentView(binding.root)

        binding.pickerTitle.text = title
        binding.btnPickerClose.setOnClickListener { dialog.dismiss() }

        val density = resources.displayMetrics.density
        val dp4 = (4 * density).toInt()
        val dp16 = (16 * density).toInt()
        val dp14 = (14 * density).toInt()
        val dp24 = (24 * density).toInt()

        binding.optionsContainer.removeAllViews()
        for ((index, option) in options.withIndex()) {
            val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { if (index > 0) it.topMargin = dp4 }
                radius =
                    resources.getDimension(com.google.android.material.R.dimen.mtrl_card_corner_radius)
                cardElevation = 0f
                setStrokeColor(
                    android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.divider_color
                        )
                    )
                )
                strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke_default)
                isClickable = true
                isFocusable = true
                val attr = android.util.TypedValue()
                requireContext().theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, attr, true
                )
                if (attr.resourceId != 0) {
                    foreground = ContextCompat.getDrawable(
                        requireContext(), attr.resourceId
                    )
                }
                setOnClickListener {
                    dialog.dismiss()
                    onSelected(index)
                }
            }

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp16, dp14, dp16, dp14)
            }

            val icon = android.widget.ImageView(requireContext()).apply {
                setImageResource(option.iconRes)
                layoutParams = LinearLayout.LayoutParams(dp24, dp24)
                val tintValue = android.util.TypedValue()
                requireContext().theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurfaceVariant, tintValue, true
                )
                imageTintList = android.content.res.ColorStateList.valueOf(tintValue.data)
            }

            val text = TextView(requireContext()).apply {
                text = option.title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.marginStart = dp16 }
            }

            row.addView(icon)
            row.addView(text)
            card.addView(row)
            binding.optionsContainer.addView(card)
        }

        dialog.show()
    }

    private fun showSinglePasswordDialog(item: HandshakeItem) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.handshake_crack_password_hint)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_crack_mode_single)
            .setView(input)
            .setPositiveButton(R.string.handshake_crack_run) { _, _ ->
                val password = input.text.toString().trim()
                if (password.isNotEmpty()) {
                    navigateToWpaCracker(item, wordlistType = 1, wordlistValue = password)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showPasswordListDialog(item: HandshakeItem) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.handshake_crack_passwords_hint)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                400
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_crack_mode_list)
            .setView(input)
            .setPositiveButton(R.string.handshake_crack_run) { _, _ ->
                val passwords = input.text.toString().lines()
                    .map { it.trim() }.filter { it.isNotBlank() }
                if (passwords.isNotEmpty()) {
                    val tempFile = File(
                        requireContext().cacheDir, "crack_paste_${System.currentTimeMillis()}.txt"
                    )
                    tempFile.writeText(passwords.joinToString("\n"))
                    navigateToWpaCracker(
                        item,
                        wordlistType = 2,
                        wordlistValue = tempFile.absolutePath
                    )
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun navigateToWpaCracker(
        item: HandshakeItem,
        wordlistType: Int = -1,
        wordlistValue: String = ""
    ) {
        val hash = item.hash22000 ?: item.hashPmkid
        if (hash.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.handshake_no_hash, Toast.LENGTH_SHORT).show()
            return
        }
        val bundle = Bundle().apply {
            putString("hash22000", hash)
            putString("fileName", item.fileName)
            putInt("wordlistType", wordlistType)
            putString("wordlistValue", wordlistValue)
        }
        try {
            findNavController().navigate(R.id.action_handshake_storage_to_wpa_cracker, bundle)
        } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.hsc_navigation_failed, e.message), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun showDeleteDialog(item: HandshakeItem) {
        val radioGroup = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(48, 16, 48, 8)
            val fileOnly = RadioButton(context).apply {
                id = 0
                text = getString(R.string.handshake_delete_file_only)
            }
            val fileAndEntry = RadioButton(context).apply {
                id = 1
                text = getString(R.string.handshake_delete_file_and_entry)
            }
            addView(fileOnly)
            addView(fileAndEntry)
            check(0)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_delete_confirm_title)
            .setMessage(item.displayName)
            .setView(radioGroup)
            .setPositiveButton(R.string.handshake_delete_action) { _, _ ->
                when (radioGroup.checkedRadioButtonId) {
                    1 -> viewModel.deleteHandshake(item)
                    else -> viewModel.deleteHandshakeFileOnly(item)
                }
            }
            .setNegativeButton(R.string.handshake_delete_cancel, null)
            .show()
    }

    private fun uploadHandshakeTo3WiFi(items: List<HandshakeItem>) {
        viewLifecycleOwner.lifecycleScope.launch {
            dbSetupViewModel.loadDbList()
            delay(300)
            val servers = dbSetupViewModel.dbList.value?.filter { it.dbType == DbType.WIFI_API }
                ?: emptyList()
            if (servers.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_3wifi_servers, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            ThreeWiFiUploader.showServerPicker(requireContext(), servers) { server ->
                val rows = items.map { item ->
                    ThreeWiFiCsvRow(
                        bssid = item.bssid ?: "",
                        essid = item.essid ?: "",
                        key = item.crackedPassword ?: "",
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
    }

    private fun onBulkDelete() {
        val selected = storageAdapter?.selectedFilePaths ?: return
        if (selected.isEmpty()) return
        val items = storageAdapter?.currentList?.filter { it.filePath in selected } ?: return
        val radioGroup = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(48, 16, 48, 8)
            val fileOnly = RadioButton(context).apply {
                id = 0
                text = getString(R.string.handshake_delete_file_only)
            }
            val fileAndEntry = RadioButton(context).apply {
                id = 1
                text = getString(R.string.handshake_delete_file_and_entry)
            }
            addView(fileOnly)
            addView(fileAndEntry)
            check(0)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_delete_confirm_title)
            .setMessage(getString(R.string.handshake_selected_count, selected.size))
            .setView(radioGroup)
            .setPositiveButton(R.string.handshake_delete_action) { _, _ ->
                viewModel.bulkDelete(radioGroup.checkedRadioButtonId, items)
                exitMultiSelect()
            }
            .setNegativeButton(R.string.handshake_delete_cancel, null)
            .show()
    }

    private fun selectedItems(): List<HandshakeItem> {
        val selected = storageAdapter?.selectedFilePaths ?: return emptyList()
        return storageAdapter?.currentList?.filter { it.filePath in selected } ?: emptyList()
    }

    private fun onBulkShare() {
        val items = selectedItems()
        if (items.isEmpty()) return
        val hasChroot = ChrootCapabilities.isAvailable(requireContext())
        val anyFileExists = items.any { it.fileExists }
        val anyHash22000 = items.any { it.hash22000 != null }
        val anyHashPmkid = items.any { it.hashPmkid != null }
        val anyHash16800 = items.any { it.hash16800 != null }
        BulkShareHandshakeBottomSheet.newInstance(
            count = items.size,
            hasChroot = hasChroot,
            anyFileExists = anyFileExists,
            anyHash22000 = anyHash22000,
            anyHashPmkid = anyHashPmkid,
            anyHash16800 = anyHash16800,
            onShareOriginal = { shareOriginalFiles(items) },
            onShareOriginalZip = { shareOriginalZip(items) },
            onShare22000 = { shareBulkMerged(items, "22000", "text/plain") },
            onShareHccapx = { shareBulkMerged(items, "hccapx", "application/octet-stream") },
            onSharePmkid = { shareBulkMerged(items, "pmkid", "text/plain") },
            onShareHccap = { shareBulkMerged(items, "hccap", "application/octet-stream") },
            onShare16800 = { shareBulkMerged(items, "16800", "text/plain") },
            onShareCapChroot = { shareConvertedCaps(items) }
        ).show(parentFragmentManager, "ShareBulkHandshakeBottomSheet")
    }

    private fun shareBulkMerged(items: List<HandshakeItem>, format: String, mimeType: String) {
        showExportProgress()
        viewModel.buildBulkShareFile(items, format) { path ->
            dismissExportProgress()
            if (path != null) {
                shareTempFile(path, mimeType)
            } else {
                Toast.makeText(
                    requireContext(),
                    R.string.handshake_share_no_hash,
                    Toast.LENGTH_SHORT
                ).show()
            }
            exitMultiSelect()
        }
    }

    private fun shareOriginalFiles(items: List<HandshakeItem>) {
        showExportProgress()
        viewModel.shareablePaths(items) { paths ->
            if (paths.isEmpty()) {
                dismissExportProgress()
                Toast.makeText(
                    requireContext(),
                    R.string.handshake_share_no_original,
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelect()
                return@shareablePaths
            }
            val uris = paths.mapNotNull { path ->
                try {
                    FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        File(path)
                    )
                } catch (_: Exception) {
                    null
                }
            }
            dismissExportProgress()
            if (uris.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.handshake_share_no_original,
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelect()
                return@shareablePaths
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.tcpdump.pcap"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/vnd.tcpdump.pcap"
                    putExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.handshake_share)))
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.hsc_cannot_share, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
            exitMultiSelect()
        }
    }

    private fun shareOriginalZip(items: List<HandshakeItem>) {
        showExportProgress()
        viewModel.shareablePaths(items) { paths ->
            if (paths.isEmpty()) {
                dismissExportProgress()
                Toast.makeText(
                    requireContext(),
                    R.string.handshake_share_no_original,
                    Toast.LENGTH_SHORT
                ).show()
                exitMultiSelect()
                return@shareablePaths
            }
            viewModel.buildBulkZip(paths) { zipPath ->
                dismissExportProgress()
                if (zipPath != null) {
                    shareTempFile(zipPath, "application/zip")
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.handshake_share_no_original,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                exitMultiSelect()
            }
        }
    }

    private fun shareConvertedCaps(items: List<HandshakeItem>) {
        showExportProgress()
        var completed = 0
        val caps = mutableListOf<String>()
        for (item in items) {
            viewModel.exportAndGetTempFile("cap", item) { path ->
                completed++
                if (path != null) caps.add(path)
                updateExportProgress(completed, items.size)
                if (completed >= items.size) {
                    dismissExportProgress()
                    if (caps.isEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            R.string.handshake_share_no_hash,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val uris = caps.mapNotNull { path ->
                            try {
                                FileProvider.getUriForFile(
                                    requireContext(),
                                    "${requireContext().packageName}.fileprovider",
                                    File(path)
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (uris.isEmpty()) {
                            Toast.makeText(
                                requireContext(),
                                R.string.handshake_share_no_original,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val intent = if (uris.size == 1) {
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "application/vnd.tcpdump.pcap"
                                    putExtra(Intent.EXTRA_STREAM, uris.first())
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            } else {
                                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "application/vnd.tcpdump.pcap"
                                    putExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            }
                            try {
                                startActivity(
                                    Intent.createChooser(
                                        intent,
                                        getString(R.string.handshake_share)
                                    )
                                )
                            } catch (e: Exception) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.hsc_cannot_share, e.message),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    exitMultiSelect()
                }
            }
        }
    }

    private fun showExportProgress() {
        dismissExportProgress()
        val progressText = TextView(requireContext()).apply {
            text = getString(R.string.handshake_share_exporting)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                android.widget.ProgressBar(
                    requireContext(),
                    null,
                    android.R.attr.progressBarStyle
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = android.view.Gravity.CENTER }
                }
            )
            addView(progressText)
        }
        exportProgressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_bulk_share_title)
            .setView(content)
            .setCancelable(false)
            .show()
        exportProgressText = progressText
    }

    private fun updateExportProgress(current: Int, total: Int) {
        exportProgressText?.text =
            getString(R.string.handshake_share_exporting_count, current, total)
    }

    private fun dismissExportProgress() {
        exportProgressDialog?.takeIf { it.isShowing }?.dismiss()
        exportProgressDialog = null
        exportProgressText = null
    }

    private fun shareTempFile(path: String, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                File(path)
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.handshake_share)))
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.hsc_cannot_share, e.message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun onBulkCopyHash22000() {
        val items = selectedItems()
        if (items.isEmpty()) return
        viewModel.collectHash22000(items) { hashes ->
            copyHash(hashes.joinToString("\n").takeIf { it.isNotBlank() })
            exitMultiSelect()
        }
    }

    private fun onBulkUpload() {
        val items = selectedItems()
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_bulk_upload)
            .setItems(
                arrayOf(
                    getString(R.string.handshake_upload_wpasec),
                    getString(R.string.handshake_upload_onlinehashcrack)
                )
            ) { _, which ->
                when (which) {
                    0 -> onBulkUploadToWpaSec(items)
                    1 -> onBulkUploadToOhc(items)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun onBulkUploadToWpaSec(items: List<HandshakeItem>) {
        val withHash = items.filter { it.hash22000 != null }
        if (withHash.isEmpty()) {
            Toast.makeText(requireContext(), R.string.handshake_no_hash, Toast.LENGTH_SHORT).show()
            return
        }
        val savedKey = viewModel.getSavedWpaSecKey()
        if (savedKey.isNullOrBlank()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.handshake_upload_wpasec)
                .setMessage(R.string.handshake_upload_wpasec_warning)
                .setPositiveButton(R.string.handshake_upload_skip) { _, _ ->
                    Toast.makeText(requireContext(), R.string.wpasec_uploading, Toast.LENGTH_SHORT)
                        .show()
                    withHash.forEach { viewModel.uploadToWpaSec(it) }
                    exitMultiSelect()
                }
                .setNegativeButton(R.string.handshake_upload_enter_key) { _, _ ->
                    showWpaSecKeyDialog {
                        withHash.forEach { viewModel.uploadToWpaSec(it) }
                        exitMultiSelect()
                    }
                }
                .setNeutralButton(R.string.close, null)
                .show()
        } else {
            Toast.makeText(requireContext(), R.string.wpasec_uploading, Toast.LENGTH_SHORT).show()
            withHash.forEach { viewModel.uploadToWpaSec(it) }
            exitMultiSelect()
        }
    }

    private fun onBulkUploadToOhc(items: List<HandshakeItem>) {
        val withHash = items.filter { it.hash22000 != null }
        if (withHash.isEmpty()) {
            Toast.makeText(requireContext(), R.string.handshake_no_hash, Toast.LENGTH_SHORT).show()
            return
        }
        val savedEmail = viewModel.getSavedEmail().orEmpty()
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.handshake_upload_email_prompt)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(savedEmail)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_upload_onlinehashcrack)
            .setMessage(getString(R.string.hsc_requires_ohc_account))
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val email = input.text.toString().trim()
                if (email.isBlank()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.handshake_upload_email_required,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                viewModel.saveEmail(email)
                Toast.makeText(
                    requireContext(),
                    R.string.handshake_share_generating,
                    Toast.LENGTH_SHORT
                ).show()
                var remaining = withHash.size
                for (item in withHash) {
                    viewModel.uploadToOnlineHashCrack(item, email) { result ->
                        remaining--
                        if (remaining == 0) {
                            val title = if (result.success) R.string.handshake_upload_success_title
                            else R.string.handshake_upload_failed
                            showResult(title, result.message)
                            exitMultiSelect()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }


    private fun showOnlineHashCrackDialog(item: HandshakeItem) {
        val savedEmail = viewModel.getSavedEmail().orEmpty()
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.handshake_upload_email_prompt)
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(savedEmail)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_upload_onlinehashcrack)
            .setMessage(getString(R.string.hsc_requires_ohc_account))
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val email = input.text.toString().trim()
                if (email.isBlank()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.handshake_upload_email_required,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                viewModel.saveEmail(email)
                Toast.makeText(
                    requireContext(),
                    R.string.handshake_share_generating,
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.uploadToOnlineHashCrack(item, email) { result ->
                    val title =
                        if (result.success) R.string.handshake_upload_success_title else R.string.handshake_upload_failed
                    showResult(title, result.message)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showWpaSecUploadDialog(item: HandshakeItem) {
        val savedKey = viewModel.getSavedWpaSecKey()
        if (savedKey.isNullOrBlank()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.handshake_upload_wpasec)
                .setMessage(R.string.handshake_upload_wpasec_warning)
                .setPositiveButton(R.string.handshake_upload_skip) { _, _ ->
                    Toast.makeText(requireContext(), R.string.wpasec_uploading, Toast.LENGTH_SHORT)
                        .show()
                    viewModel.uploadToWpaSec(item)
                }
                .setNegativeButton(R.string.handshake_upload_enter_key) { _, _ ->
                    showWpaSecKeyDialog { viewModel.uploadToWpaSec(item) }
                }
                .setNeutralButton(R.string.close, null)
                .show()
        } else {
            Toast.makeText(requireContext(), R.string.wpasec_uploading, Toast.LENGTH_SHORT).show()
            viewModel.uploadToWpaSec(item)
        }
    }

    private fun showWpaSecKeyDialog(onKeySet: () -> Unit) {
        val savedKey = viewModel.getSavedWpaSecKey().orEmpty()
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.handshake_upload_api_key_prompt)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(savedKey)
            setPadding(48, 32, 48, 32)
        }
        val messageLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(requireContext()).apply {
                text = getString(R.string.wpasec_key_message)
                textSize = 12f
                setPadding(24, 8, 24, 8)
            })
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wpasec_key_title)
            .setView(messageLayout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotBlank()) {
                    viewModel.saveWpaSecKey(key)
                    onKeySet()
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.handshake_upload_key_required,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showResult(titleRes: Int, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun exitMultiSelect() {
        storageAdapter?.isMultiSelectMode = false
        viewModel.setMultiSelectMode(false)
        updateSelectButton(false)
    }

    private fun highlightItemIfRequested(items: List<HandshakeItem>) {
        val targetPath = arguments?.getString("filePath").orEmpty()
        if (targetPath.isEmpty() || storageAdapter == null) return
        val idx = items.indexOfFirst { it.filePath == targetPath }
        if (idx < 0) return
        binding.recyclerViewStorage.post {
            binding.recyclerViewStorage.scrollToPosition(idx)
            val viewHolder = binding.recyclerViewStorage.findViewHolderForAdapterPosition(idx)
            viewHolder?.itemView?.alpha = 0.4f
            viewHolder?.itemView?.animate()?.alpha(1f)?.setDuration(800)?.start()
        }
    }

    private fun shareHandshake(item: HandshakeItem) {
        val hasChroot = ChrootCapabilities.isAvailable(requireContext())
        if (!item.fileExists) {
            ShareHandshakeBottomSheet.newInstance(
                item = item,
                hasChroot = hasChroot,
                onShareCap = { shareAsCap(item) },
                onShareHccapx = { exportAndShare(item, "hccapx") },
                onShare22000 = { exportAndShare(item, "22000") },
                onSharePmkid = { exportAndShare(item, "pmkid") },
                onShareHccap = { exportAndShare(item, "hccap") },
                onShare16800 = { exportAndShare(item, "16800") },
                onShareCapChroot = { exportAndShare(item, "cap") }
            ).show(parentFragmentManager, "ShareHandshakeBottomSheet")
            return
        }
        viewModel.ensureFileAccessible(item.filePath) { resolvedPath ->
            if (resolvedPath == null) {
                Toast.makeText(requireContext(), getString(R.string.hsc_file_not_accessible), Toast.LENGTH_SHORT).show()
                return@ensureFileAccessible
            }
            val accessibleItem = item.copy(filePath = resolvedPath)
            ShareHandshakeBottomSheet.newInstance(
                item = accessibleItem,
                hasChroot = hasChroot,
                onShareCap = { shareAsCap(accessibleItem) },
                onShareHccapx = { exportAndShare(accessibleItem, "hccapx") },
                onShare22000 = { exportAndShare(accessibleItem, "22000") },
                onSharePmkid = { exportAndShare(accessibleItem, "pmkid") },
                onShareHccap = { exportAndShare(accessibleItem, "hccap") },
                onShare16800 = { exportAndShare(accessibleItem, "16800") },
                onShareCapChroot = { exportAndShare(accessibleItem, "cap") }
            ).show(parentFragmentManager, "ShareHandshakeBottomSheet")
        }
    }

    private fun shareAsCap(item: HandshakeItem) {
        val file = File(item.filePath)
        val uri = try {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
        } catch (e1: Exception) {
            try {
                val cacheDir = File(requireContext().cacheDir, "handshake_share")
                cacheDir.mkdirs()
                val cacheFile = File(cacheDir, file.name)
                com.topjohnwu.superuser.Shell.cmd("cp '${file.absolutePath}' '${cacheFile.absolutePath}'")
                    .exec()
                if (cacheFile.exists()) {
                    FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        cacheFile
                    )
                } else {
                    null
                }
            } catch (e2: Exception) {
                null
            }
        }
        if (uri != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.handshake_share)))
        } else {
            Toast.makeText(requireContext(), getString(R.string.hsc_cannot_share_file), Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportAndShare(item: HandshakeItem, format: String) {
        Toast.makeText(requireContext(), R.string.handshake_share_generating, Toast.LENGTH_SHORT)
            .show()
        viewModel.exportAndGetTempFile(format, item) { tempPath ->
            if (tempPath != null) {
                val mimeType = when (format) {
                    "22000", "pmkid", "16800" -> "text/plain"
                    "cap" -> "application/vnd.tcpdump.pcap"
                    else -> "application/octet-stream"
                }
                shareTempFile(tempPath, mimeType)
            } else {
                Toast.makeText(requireContext(), getString(R.string.hsc_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyHash(text: String?) {
        if (text.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.handshake_no_hash, Toast.LENGTH_SHORT).show()
            return
        }
        val clip = ClipData.newPlainText("Hash", text)
        val manager =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(clip)
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun showHandshakeDetails(item: HandshakeItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_handshake_details, null)


        dialogView.findViewById<TextView>(R.id.detailFileName).text = item.fileName
        dialogView.findViewById<TextView>(R.id.detailFilePath).text = item.filePath
        dialogView.findViewById<TextView>(R.id.detailFileSize).text = item.formattedSize
        dialogView.findViewById<TextView>(R.id.detailFileDate).text = item.dateFormatted
        if (item.originalFormat != null) {
            dialogView.findViewById<TextView>(R.id.detailFormat).text =
                getString(R.string.handshake_format_label, item.originalFormat.uppercase())
        } else {
            dialogView.findViewById<TextView>(R.id.detailFormat).visibility = View.GONE
        }
        val existsView = dialogView.findViewById<TextView>(R.id.detailFileExists)
        if (!item.fileExists) {
            existsView.visibility = View.VISIBLE
            existsView.text = getString(R.string.handshake_file_not_found)
            existsView.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
        }


        if (item.essid != null) {
            dialogView.findViewById<TextView>(R.id.detailEssid).text = item.essid
            dialogView.findViewById<View>(R.id.btnCopyEssid).setOnClickListener {
                copyToClipboard(item.essid, "SSID")
            }
        } else {
            dialogView.findViewById<View>(R.id.essidRow).visibility = View.GONE
        }
        if (item.bssid != null) {
            dialogView.findViewById<TextView>(R.id.detailBssid).text = item.bssid
            dialogView.findViewById<View>(R.id.btnCopyBssid).setOnClickListener {
                copyToClipboard(item.bssid, "BSSID")
            }
        } else {
            dialogView.findViewById<View>(R.id.bssidRow).visibility = View.GONE
        }
        val channelText = buildString {
            item.channel?.let { append(getString(R.string.hsc_channel, it)) }
            item.band?.let { append(" ($it)") }
        }
        if (channelText.isNotBlank()) {
            dialogView.findViewById<TextView>(R.id.detailChannel).text = channelText
        } else {
            dialogView.findViewById<View>(R.id.detailChannel).visibility = View.GONE
        }
        if (item.akm != null) {
            dialogView.findViewById<TextView>(R.id.detailAkm).text = getString(R.string.hsc_akm, item.akm)
        } else {
            dialogView.findViewById<View>(R.id.detailAkm).visibility = View.GONE
        }
        val cipherText = listOfNotNull(item.groupCipher, item.pairwiseCipher).joinToString(" / ")
        if (cipherText.isNotBlank()) {
            dialogView.findViewById<TextView>(R.id.detailCipher).text = getString(R.string.hsc_cipher, cipherText)
        } else {
            dialogView.findViewById<View>(R.id.detailCipher).visibility = View.GONE
        }
        if (item.rssi != null) {
            dialogView.findViewById<TextView>(R.id.detailRssi).text = getString(R.string.hsc_rssi, item.rssi)
        } else {
            dialogView.findViewById<View>(R.id.detailRssi).visibility = View.GONE
        }
        val apsView = dialogView.findViewById<TextView>(R.id.detailApsInFile)
        if (item.apsInFile != null) {
            apsView.text = item.apsInFile
            apsView.visibility = View.VISIBLE
        }


        val validityView = dialogView.findViewById<TextView>(R.id.detailValidity)
        if (item.isValid != null) {
            validityView.text =
                getString(R.string.hsc_validity, if (item.isValid) getString(R.string.handshake_valid) else getString(R.string.handshake_invalid))
            validityView.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (item.isValid) R.color.success_green else R.color.error_red
                )
            )
        } else {
            validityView.visibility = View.GONE
        }
        dialogView.findViewById<TextView>(R.id.detailCounts).text =
            getString(R.string.hsc_counts, item.handshakeCount, item.eapolCount, item.pmkidCount)
        if (item.keyver != null) {
            dialogView.findViewById<TextView>(R.id.detailKeyver).text =
                getString(R.string.hsc_keyver, if (item.keyver == 1) "" else item.keyver.toString())
        } else {
            dialogView.findViewById<View>(R.id.detailKeyver).visibility = View.GONE
        }
        if (item.endianness != null) {
            dialogView.findViewById<TextView>(R.id.detailEndianness).text =
                getString(R.string.hsc_endian, item.endianness)
        } else {
            dialogView.findViewById<View>(R.id.detailEndianness).visibility = View.GONE
        }
        dialogView.findViewById<TextView>(R.id.detailNonceError).text =
            item.nonceErrorCorrection?.let { getString(R.string.hsc_nc, it) }
                ?: getString(R.string.hsc_nc_not_detected)


        dialogView.findViewById<TextView>(R.id.detailM1).text = item.eapolM1Count.toString()
        dialogView.findViewById<TextView>(R.id.detailM2).text = item.eapolM2Count.toString()
        dialogView.findViewById<TextView>(R.id.detailM3).text = item.eapolM3Count.toString()
        dialogView.findViewById<TextView>(R.id.detailM4).text = item.eapolM4Count.toString()
        dialogView.findViewById<TextView>(R.id.detailBeacon).text = getString(R.string.hsc_beacon, item.beaconCount)
        dialogView.findViewById<TextView>(R.id.detailAssocReq).text = getString(R.string.hsc_assoc, item.assocReqCount)
        dialogView.findViewById<TextView>(R.id.detailAuth).text = getString(R.string.hsc_auth, item.authCount)
        dialogView.findViewById<TextView>(R.id.detailProbeReq).text = getString(R.string.hsc_probe, item.probeReqCount)
        val clientsFormatted = item.clients?.replace(",", ", ")?.trim()
        val clientsView = dialogView.findViewById<TextView>(R.id.detailClients)
        if (clientsFormatted.isNullOrBlank()) {
            clientsView.visibility = View.GONE
        } else {
            clientsView.text = getString(R.string.hsc_clients, clientsFormatted)
            clientsView.visibility = View.VISIBLE
        }


        val locSection = dialogView.findViewById<View>(R.id.detailLocationSection)
        if (item.latitude != null && item.longitude != null) {
            locSection.visibility = View.VISIBLE
            val coordStr = "${"%.6f".format(item.latitude)}, ${"%.6f".format(item.longitude)}"
            dialogView.findViewById<TextView>(R.id.detailCoordinates).text = coordStr
            dialogView.findViewById<View>(R.id.btnCopyCoordinates).setOnClickListener {
                copyToClipboard(coordStr, getString(R.string.handshake_copy_coordinates))
            }
            dialogView.findViewById<View>(R.id.btnShowOnMap).setOnClickListener {
                openOnMap(item.latitude, item.longitude)
            }
        }


        val hash22000Section = dialogView.findViewById<View>(R.id.detailHash22000Section)
        if (item.hash22000 != null) {
            val hashView = dialogView.findViewById<TextView>(R.id.detailHash22000Text)
            hashView.text = item.hash22000
            hashView.setTextIsSelectable(true)
            hashView.setTextColor(ContextCompat.getColor(requireContext(), R.color.code_block_text))
            dialogView.findViewById<View>(R.id.btnCopyHash22000)
                .setOnClickListener { copyHash(item.hash22000) }
        } else {
            hash22000Section.visibility = View.GONE
        }
        val pmkidSection = dialogView.findViewById<View>(R.id.detailPmkidSection)
        if (item.hashPmkid != null) {
            val hashView = dialogView.findViewById<TextView>(R.id.detailHashPmkidText)
            hashView.text = item.hashPmkid
            hashView.setTextIsSelectable(true)
            hashView.setTextColor(ContextCompat.getColor(requireContext(), R.color.code_block_text))
            dialogView.findViewById<View>(R.id.btnCopyHashPmkid)
                .setOnClickListener { copyHash(item.hashPmkid) }
        } else {
            pmkidSection.visibility = View.GONE
        }
        val hash16800Section = dialogView.findViewById<View>(R.id.detailHash16800Section)
        if (item.hash16800 != null) {
            val hashView = dialogView.findViewById<TextView>(R.id.detailHash16800Text)
            hashView.text = item.hash16800
            hashView.setTextIsSelectable(true)
            hashView.setTextColor(ContextCompat.getColor(requireContext(), R.color.code_block_text))
            dialogView.findViewById<View>(R.id.btnCopyHash16800)
                .setOnClickListener { copyHash(item.hash16800) }
        } else {
            hash16800Section.visibility = View.GONE
        }
        val md5Section = dialogView.findViewById<View>(R.id.detailHashMd5Section)
        if (item.hashDedupMd5 != null) {
            val hashView = dialogView.findViewById<TextView>(R.id.detailHashMd5Text)
            hashView.text = item.hashDedupMd5
            hashView.setTextIsSelectable(true)
            hashView.setTextColor(ContextCompat.getColor(requireContext(), R.color.code_block_text))
            dialogView.findViewById<View>(R.id.btnCopyHashMd5)
                .setOnClickListener { copyHash(item.hashDedupMd5) }
        } else {
            md5Section.visibility = View.GONE
        }


        val wpaSecText = buildString {
            if (item.uploadedToWpaSec) {
                append(getString(R.string.hsc_wpasec_uploaded))
                if (item.wpasecChecked) {
                    append(if (item.wpasecPasswordFound) getString(R.string.hsc_wpasec_password_found) else getString(R.string.hsc_wpasec_not_found))
                }
            } else {
                append(getString(R.string.hsc_wpasec_not_uploaded))
            }
        }
        val wpaSecView = dialogView.findViewById<TextView>(R.id.detailWpaSecStatus)
        wpaSecView.text = wpaSecText
        wpaSecView.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                when {
                    item.wpasecPasswordFound -> R.color.success_green
                    item.uploadedToWpaSec -> R.color.text_secondary
                    else -> R.color.text_secondary
                }
            )
        )
        val ohcView = dialogView.findViewById<TextView>(R.id.detailOhcStatus)
        val ohcRequestView = dialogView.findViewById<TextView>(R.id.detailOhcRequestId)
        if (item.uploadedToOhc) {
            val email = item.ohcEmail ?: viewModel.getSavedEmail()
            ohcView.text = getString(R.string.hsc_ohc_uploaded, email ?: getString(R.string.hsc_no_email))
            ohcView.setTextColor(ContextCompat.getColor(requireContext(), R.color.success_green))
            ohcView.visibility = View.VISIBLE
            if (item.requestIdOhc != null) {
                ohcRequestView.text = getString(R.string.hsc_ohc_request, item.requestIdOhc)
                ohcRequestView.visibility = View.VISIBLE
            }
        } else {
            ohcView.text = getString(R.string.hsc_ohc_not_uploaded)
            ohcView.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            ohcView.visibility = View.VISIBLE
        }
        if (item.crackedPassword != null) {
            dialogView.findViewById<TextView>(R.id.detailCrackedPassword).apply {
                text = getString(R.string.handshake_key_found, item.crackedPassword)
                visibility = View.VISIBLE
            }
        }


        dialogView.findViewById<View>(R.id.btnDetailVerify).apply {
            isEnabled = item.fileExists
            alpha = if (item.fileExists) 1f else 0.4f
            setOnClickListener {
                viewModel.verifyStoredHandshake(item)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.hsc_verifying, item.fileName),
                    Toast.LENGTH_SHORT
                )
                    .show()
            }
        }
        dialogView.findViewById<View>(R.id.btnDetailCrack).apply {
            val hasHashes = item.hash22000 != null || item.hashPmkid != null
            isEnabled = hasHashes
            alpha = if (hasHashes) 1f else 0.4f
            setOnClickListener {
                showCrackBottomSheet(item)
            }
        }
        dialogView.findViewById<View>(R.id.btnDetailShare).apply {
            val shareable = item.fileExists || item.hash22000 != null ||
                    item.hashPmkid != null || item.hash16800 != null
            isEnabled = shareable
            alpha = if (shareable) 1f else 0.4f
            setOnClickListener {
                shareHandshake(item)
            }
        }
        dialogView.findViewById<View>(R.id.btnDetailMore).setOnClickListener { v ->
            val moreActions = mutableListOf<Pair<String, () -> Unit>>()
            if (item.hash22000 != null || item.fileExists) {
                moreActions.add(
                    getString(R.string.handshake_export_hccapx) to {
                        exportAndShare(
                            item,
                            "hccapx"
                        )
                    }
                )
                moreActions.add(
                    getString(R.string.handshake_export_22000) to { exportAndShare(item, "22000") }
                )
            }
            if (item.hash22000 != null ||
                (item.uploadedToWpaSec && !item.bssid.isNullOrBlank() && !item.essid.isNullOrBlank())
            ) {
                moreActions.add(
                    (if (item.uploadedToWpaSec) getString(R.string.wpasec_check)
                    else getString(R.string.handshake_upload_wpasec)) to {
                        if (item.uploadedToWpaSec) viewModel.checkOnWpaSec(item)
                        else showWpaSecUploadDialog(item)
                    }
                )
            }
            if (item.hash22000 != null) {
                moreActions.add(
                    getString(R.string.handshake_upload_onlinehashcrack) to {
                        showOnlineHashCrackDialog(item)
                    }
                )
            }
            moreActions.add(getString(R.string.delete) to { showDeleteDialog(item) })

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.hsc_actions))
                .setItems(moreActions.map { it.first }.toTypedArray()) { _, which ->
                    moreActions[which].second()
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }


        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.displayName)
            .setView(dialogView)
            .setPositiveButton(R.string.close, null)

        dialog.show()
    }


    private fun copyToClipboard(text: String, label: String) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), getString(R.string.hsc_copied, label), Toast.LENGTH_SHORT).show()
    }

    private fun openOnMap(lat: Double, lon: Double) {
        try {
            val bundle = Bundle().apply {
                putFloat("latitude", lat.toFloat())
                putFloat("longitude", lon.toFloat())
            }
            findNavController().navigate(R.id.nav_wifi_map, bundle)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.hsc_cannot_open_map, e.message), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun showResultDialog(password: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_crack)
            .setMessage(getString(R.string.handshake_key_found, password))
            .setPositiveButton(R.string.copied_to_clipboard) { _, _ ->
                copyHash(password)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
