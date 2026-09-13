package com.lsd.wififrankenstein.ui.personalmap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.BottomSheetPersonalDatabaseBinding
import com.lsd.wififrankenstein.databinding.BottomSheetPersonalPointInfoBinding
import com.lsd.wififrankenstein.databinding.BottomSheetDbMatchBinding
import com.lsd.wififrankenstein.databinding.DialogEditPersonalPointBinding
import com.lsd.wififrankenstein.databinding.FragmentPersonalDatabaseBinding
import com.lsd.wififrankenstein.databinding.ItemInfoRowBinding
import com.lsd.wififrankenstein.databinding.ItemMatchFieldBinding
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalMapDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalPointDetail
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class PersonalDatabaseFragment : Fragment() {

    private var _binding: FragmentPersonalDatabaseBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PersonalWiFiMapViewModel by activityViewModels()
    private val recordsAdapter = RecentNetworksAdapter { entry ->
        if (entry.id > 0L) showPointInfoSheet(entry)
    }
    private val numberFormat = NumberFormat.getIntegerInstance()

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.exportWigleCsv(uri, gzip = false) { ok ->
            safeSnackbar(if (ok) R.string.pm_export_done else R.string.pm_export_failed)
        }
    }

    private val exportGzLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.exportWigleCsv(uri, gzip = true) { ok ->
            safeSnackbar(if (ok) R.string.pm_export_done else R.string.pm_export_failed)
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.importWigleCsv(uri) { inserted, updated ->
            if (inserted < 0) safeSnackbar(R.string.pm_import_failed)
            else safeSnackbar(getString(R.string.pm_import_done, inserted, updated))
        }
    }

    private val uploadLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.uploadWigleCsv(uri) { ok, message ->
            safeSnackbar(getString(R.string.pm_upload_result, if (ok) getString(R.string.ok) else message))
        }
    }

    private val importWifiLocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.importWifiLocTracker(uri) { count ->
            if (count < 0) safeSnackbar(R.string.pm_import_failed)
            else safeSnackbar(getString(R.string.pm_import_wifiloc_done, count))
        }
    }

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.backupPersonalDatabase(uri) { ok ->
            safeSnackbar(if (ok) R.string.pm_backup_done else R.string.pm_backup_failed)
        }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val current = viewModel.stats.value?.total?.toInt() ?: 0
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pm_restore)
            .setMessage(resources.getQuantityString(R.plurals.pm_restore_confirm, current, current))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.pm_restore) { _, _ ->
                viewModel.restorePersonalDatabase(uri) { ok ->
                    safeSnackbar(if (ok) R.string.pm_restore_done else R.string.pm_restore_failed)
                }
            }
            .show()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalDatabaseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recordsAdapter.showBadge = false
        binding.pmRecordsRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recordsAdapter

            isNestedScrollingEnabled = true
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int
                ) {
                    if (dy <= 0) return

                    if (binding.pmSearch.text?.toString()?.isNotBlank() == true) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val total = lm.itemCount
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (total > 0 && lastVisible >= total - SCROLL_LOAD_THRESHOLD) {
                        viewModel.loadMoreRecords()
                    }
                }
            })
        }

        setupSwipeToDelete()

        viewModel.stats.observe(viewLifecycleOwner) { stats ->
            if (!isAdded) return@observe
            binding.pmStatTotal.text = numberFormat.format(stats.total)
            binding.pmStatOpen.text = numberFormat.format(stats.openCount)
            binding.pmStatCross.text = numberFormat.format(stats.crossFound)
            binding.pmStatWpasec.text = numberFormat.format(stats.wpasecFound)
            binding.pmStatWps.text = numberFormat.format(stats.wpsCount)
        }

        viewModel.records.observe(viewLifecycleOwner) { records ->
            if (!isAdded) return@observe
            recordsAdapter.submitList(records)
            updateRecordsEmpty(records.isEmpty(), searching = isSearching())
        }

        viewModel.isBusy.observe(viewLifecycleOwner) { busy ->
            if (!isAdded) return@observe
            binding.pmBusy.visibility = if (busy == true) View.VISIBLE else View.GONE
            binding.pmFabManage.isEnabled = busy != true
        }

        binding.pmSearch.addTextChangedListener { text ->
            val q = text?.toString().orEmpty()
            if (q.isBlank()) {
                viewModel.refreshRecords()
            } else {
                viewModel.searchRecords(q)
            }
        }

        binding.pmFabManage.setOnClickListener {
            showManagementBottomSheet()
        }

        viewModel.dataVersion.observe(viewLifecycleOwner) {
            if (!isAdded) return@observe
            viewModel.reloadRecords()
            (parentFragment as? PersonalWiFiMapFragment)?.refreshMapTab()
        }
    }

    private fun showManagementBottomSheet() {
        val sheet = BottomSheetDialog(requireContext())
        val sheetBinding = BottomSheetPersonalDatabaseBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        val wigleMime = arrayOf(
            "text/csv", "text/comma-separated-values",
            "application/gzip", "application/octet-stream"
        )

        sheetBinding.pmButtonImportWigle.setOnClickListener {
            sheet.dismiss()
            importLauncher.launch(wigleMime)
        }
        sheetBinding.pmButtonExportWigle.setOnClickListener {
            sheet.dismiss()
            exportCsvLauncher.launch("wigle_personal.csv")
        }
        sheetBinding.pmButtonExportWigleGz.setOnClickListener {
            sheet.dismiss()
            exportGzLauncher.launch("wigle_personal.csv.gz")
        }
        sheetBinding.pmButtonUploadWigle.setOnClickListener {
            sheet.dismiss()
            uploadLauncher.launch(wigleMime)
        }

        sheetBinding.pmButtonUpload3wifiApp.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val hasAccount = com.lsd.wififrankenstein.network.ThreeWifiAppSession
                .hasAuthenticated(requireContext())
            sheetBinding.pmButtonUpload3wifiApp.visibility =
                if (hasAccount) View.VISIBLE else View.GONE
        }
        sheetBinding.pmButtonUpload3wifiApp.setOnClickListener {
            sheet.dismiss()
            viewModel.uploadWardrivingTo3WifiApp { report ->
                if (!isAdded) return@uploadWardrivingTo3WifiApp
                val message = if (report.uploaded > 0) {
                    getString(
                        R.string.pm_upload_3wifi_app_result,
                        report.uploaded,
                        report.failed
                    )
                } else {
                    getString(
                        R.string.pm_upload_3wifi_app_failed,
                        report.error ?: getString(R.string.unknown_error)
                    )
                }
                safeSnackbar(message)
            }
        }

        sheetBinding.pmButtonImportWifiloc.setOnClickListener {
            sheet.dismiss()
            importWifiLocLauncher.launch(
                arrayOf("application/octet-stream", "application/x-sqlite3")
            )
        }
        sheetBinding.pmButtonBackup.setOnClickListener {
            sheet.dismiss()
            backupLauncher.launch("personal_wifi_map.db")
        }
        sheetBinding.pmButtonRestore.setOnClickListener {
            sheet.dismiss()
            restoreLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3"))
        }

        sheetBinding.pmButtonRecheck.setOnClickListener {
            sheet.dismiss()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pm_recheck)
                .setMessage(R.string.pm_recheck_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pm_recheck) { _, _ ->
                    viewModel.resetCrossChecks { safeSnackbar(R.string.pm_recheck_done) }
                }
                .show()
        }
        sheetBinding.pmButtonStats.setOnClickListener {
            sheet.dismiss()
            findNavController().navigate(R.id.nav_personal_map_stats)
        }
        sheetBinding.pmButtonClear.setOnClickListener {
            sheet.dismiss()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_personal_map)
                .setMessage(R.string.clear_personal_map_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewModel.clearMap()
                    safeSnackbar(R.string.pm_clear_done)
                }
                .show()
        }

        sheet.show()
    }

    private fun isSearching(): Boolean =
        binding.pmSearch.text?.toString()?.isNotBlank() == true

    private fun setupSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val entry = recordsAdapter.currentList.getOrNull(position) ?: return
                if (entry.id <= 0L) {
                    recordsAdapter.notifyItemChanged(position)
                    return
                }
                viewModel.deleteNetwork(entry.id) { record ->
                    if (record == null) {
                        safeSnackbar(R.string.pm_delete_failed)
                        return@deleteNetwork
                    }
                    val root = _binding?.root ?: return@deleteNetwork
                    Snackbar.make(root, R.string.pm_delete_done, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) {
                            viewModel.restoreNetwork(record) { ok ->
                                safeSnackbar(
                                    if (ok) R.string.pm_delete_restored else R.string.pm_delete_failed
                                )
                            }
                        }
                        .show()
                }
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.pmRecordsRecycler)
    }

    private fun showPointInfoSheet(entry: PersonalMapLogEntry) {
        val ctx = context ?: return
        val sheet = BottomSheetDialog(ctx)
        val sheetBinding = BottomSheetPersonalPointInfoBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        sheetBinding.infoSsid.text = entry.ssid.ifBlank { getString(R.string.unknown_ssid) }
        sheetBinding.infoBssid.text = entry.bssid

        sheetBinding.btnCopySsid.setOnClickListener {
            copyToClipboard("SSID", entry.ssid.ifBlank { getString(R.string.unknown_ssid) })
        }
        sheetBinding.btnCopyBssid.setOnClickListener {
            copyToClipboard("BSSID", entry.bssid)
        }

        sheetBinding.infoButtonRename.setOnClickListener {
            sheet.dismiss()
            showEditNameDialog(entry)
        }

        sheetBinding.infoButtonShowOnMap.setOnClickListener {
            sheet.dismiss()
            val parentFragment = parentFragment
            if (parentFragment is PersonalWiFiMapFragment) {
                parentFragment.switchToMapTab(entry.bssid)
            }
        }

        val matchAdapter = DbMatchAdapter { match -> showMatchDetailDialog(match) }
        sheetBinding.matchesList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = matchAdapter
        }

        var crossLivePresent = false
        var wpasecLivePresent = false

        fun applyCrossLive() {
            sheetBinding.infoCross.text = checkedStatusText(
                getString(R.string.pm_info_cross_present),
                System.currentTimeMillis()
            )
            sheetBinding.infoCross.setTextColor(
                ContextCompat.getColor(ctx, R.color.signal_good)
            )
        }

        fun applyWpasecLive() {
            sheetBinding.infoWpasec.text = checkedStatusText(
                getString(R.string.pm_info_wpasec_present),
                System.currentTimeMillis()
            )
            sheetBinding.infoWpasec.setTextColor(
                ContextCompat.getColor(ctx, R.color.signal_good)
            )
        }

        fun loadMatches() {
            sheetBinding.btnCheckLocalDb.isEnabled = false
            sheetBinding.btnCheckLocalDb.text = getString(R.string.pm_checking)
            viewModel.findMatchesForPoint(entry.bssid) { matches ->
                if (!isAdded) return@findMatchesForPoint
                sheetBinding.btnCheckLocalDb.isEnabled = true
                sheetBinding.btnCheckLocalDb.text = getString(R.string.pm_check_local_db)
                matchAdapter.submitList(matches)
                sheetBinding.matchesList.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
                sheetBinding.matchesEmpty.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
                if (matches.isNotEmpty()) {
                    crossLivePresent = true
                    applyCrossLive()
                    viewModel.markCrossPresent(entry.bssid)
                }
            }
        }
        loadMatches()
        sheetBinding.btnCheckLocalDb.setOnClickListener { loadMatches() }

        sheetBinding.btnCheckWpaSec.setOnClickListener {
            sheetBinding.btnCheckWpaSec.isEnabled = false
            sheetBinding.btnCheckWpaSec.text = getString(R.string.pm_checking)
            viewModel.checkWpaSecForPoint(entry.bssid, entry.ssid.ifBlank { "" }) { found, message ->
                if (!isAdded) return@checkWpaSecForPoint
                sheetBinding.btnCheckWpaSec.isEnabled = true
                sheetBinding.btnCheckWpaSec.text = getString(R.string.pm_check_wpasec)
                if (found) {
                    wpasecLivePresent = true
                    applyWpasecLive()
                    viewModel.markWpasecPresent(entry.bssid)
                }
                safeSnackbar(message)
            }
        }

        sheetBinding.btnCheck3Wifi.setOnClickListener {
            sheetBinding.btnCheck3Wifi.isEnabled = false
            sheetBinding.btnCheck3Wifi.text = getString(R.string.pm_checking)
            viewModel.check3WifiApiForPoint(entry.bssid) { found, message ->
                if (!isAdded) return@check3WifiApiForPoint
                sheetBinding.btnCheck3Wifi.isEnabled = true
                sheetBinding.btnCheck3Wifi.text = getString(R.string.pm_check_3wifi)
                showApiResultDialog(found, message)
            }
        }

        var pointDetail: PersonalPointDetail? = null

        sheetBinding.btnSubmit3WifiApp.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val hasAccount =
                com.lsd.wififrankenstein.network.ThreeWifiAppSession.hasAuthenticated(requireContext())
            sheetBinding.btnSubmit3WifiApp.visibility =
                if (hasAccount) View.VISIBLE else View.GONE
        }
        sheetBinding.btnSubmit3WifiApp.setOnClickListener {
            sheetBinding.btnSubmit3WifiApp.isEnabled = false
            sheetBinding.btnSubmit3WifiApp.text = getString(R.string.pm_checking)
            viewLifecycleOwner.lifecycleScope.launch {
                val server =
                    com.lsd.wififrankenstein.network.ThreeWifiAppSession.authenticatedServer(
                        requireContext()
                    )
                val token = server?.let {
                    com.lsd.wififrankenstein.network.ThreeWifiAppSession.currentToken(
                        requireContext(), it
                    )
                }
                if (server == null || token == null) {
                    safeSnackbar(R.string.api3_error_auth_required)
                } else {
                    val detail = pointDetail
                    val result =
                        com.lsd.wififrankenstein.network.ThreeWifiAppUploader.submitAll(
                            requireContext(),
                            server,
                            token,
                            listOf(
                                com.lsd.wififrankenstein.network.ThreeWifiAppUploader.SubmitRecord(
                                    bssid = entry.bssid,
                                    ssid = entry.ssid.ifBlank { "" },
                                    security = detail?.securityType ?: detail?.security,
                                    latitude = detail?.latitude,
                                    longitude = detail?.longitude
                                )
                            )
                        )
                    val msg = if (result.uploaded > 0) {
                        getString(
                            R.string.pm_upload_3wifi_app_result,
                            result.uploaded,
                            result.failed
                        )
                    } else {
                        getString(
                            R.string.pm_upload_3wifi_app_failed,
                            result.error ?: getString(R.string.unknown_error)
                        )
                    }
                    safeSnackbar(msg)
                }
                sheetBinding.btnSubmit3WifiApp.isEnabled = true
                sheetBinding.btnSubmit3WifiApp.text = getString(R.string.pm_submit_3wifi_app)
            }
        }

        viewModel.getPointDetail(entry.id) { detail ->
            if (!isAdded) return@getPointDetail
            if (detail == null) {
                sheet.dismiss()
            } else {
                pointDetail = detail
                bindPointInfo(sheetBinding, detail)
                if (crossLivePresent) applyCrossLive()
                if (wpasecLivePresent) applyWpasecLive()
            }
        }

        viewModel.getNote(entry.bssid) { note ->
            if (!isAdded) return@getNote
            sheetBinding.infoNoteEdit.setText(note)
        }
        sheetBinding.btnSaveNote.setOnClickListener {
            val note = sheetBinding.infoNoteEdit.text?.toString().orEmpty()
            viewModel.setNote(entry.bssid, note)
            Snackbar.make(sheetBinding.root, R.string.pm_save_note, Snackbar.LENGTH_SHORT).show()
        }

        fun refreshTags() {
            viewModel.getTags(entry.bssid) { tags ->
                if (!isAdded) return@getTags
                sheetBinding.infoChipGroupTags.removeAllViews()
                tags.forEach { (tagName, _) ->
                    val chip = Chip(ctx).apply {
                        text = tagName
                        isCloseIconVisible = true
                        setOnCloseIconClickListener {
                            viewModel.removeTag(entry.bssid, tagName)
                            refreshTags()
                        }
                    }
                    sheetBinding.infoChipGroupTags.addView(chip)
                }
            }
        }
        refreshTags()
        sheetBinding.btnAddTag.setOnClickListener {
            val editText = android.widget.EditText(ctx).apply {
                hint = getString(R.string.pm_tag_hint)
                setPadding(48, 32, 48, 16)
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.pm_add_tag)
                .setView(editText)
                .setPositiveButton(R.string.pm_add_tag) { _, _ ->
                    val tag = editText.text?.toString()?.trim().orEmpty()
                    if (tag.isNotBlank()) {
                        viewModel.addTag(entry.bssid, tag, 0)
                        refreshTags()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        sheet.show()
    }

    private fun bindPointInfo(b: BottomSheetPersonalPointInfoBinding, d: PersonalPointDetail) {
        b.infoSsid.text = d.ssid.ifBlank { getString(R.string.unknown_ssid) }
        b.infoBssid.text = d.bssid
        b.infoCoordsNote.visibility = View.VISIBLE
        val defaultStatusColor = b.infoWpasec.currentTextColor

        val rssi = if (d.level > -100) getString(R.string.rssi_dbm, d.level)
        else getString(R.string.not_available)
        val reliable = getString(if (d.isReliable) R.string.pm_info_yes else R.string.pm_info_no)
        val channel = when {
            d.channel > 0 && d.frequency > 0 ->
                getString(R.string.channel_with_frequency, d.channel, d.frequency)

            d.channel > 0 -> getString(R.string.channel_format, d.channel)
            else -> getString(R.string.not_available)
        }
        populateInfoRows(
            b.infoRadioRows,
            listOf(
                R.string.pm_info_rssi to rssi,
                R.string.pm_info_reliable to reliable,
                R.string.pm_info_channel to channel,
                R.string.pm_info_security to securityInfoLabel(d.securityType),
                R.string.pm_info_wps to getString(
                    if (d.isWps) R.string.pm_info_yes else R.string.pm_info_no
                ),
                R.string.pm_info_passpoint to getString(
                    if (d.isPasspoint) R.string.pm_info_yes else R.string.pm_info_no
                ),
                R.string.pm_info_hidden to getString(
                    if (d.isHidden) R.string.pm_info_yes else R.string.pm_info_no
                ),
                R.string.pm_info_authmode to
                    (d.security?.takeIf { it.isNotBlank() } ?: getString(R.string.not_available))
            )
        )

        val coords = if (d.latitude != 0.0 || d.longitude != 0.0) {
            String.format(Locale.US, "%.5f, %.5f", d.latitude, d.longitude)
        } else {
            getString(R.string.not_available)
        }
        val accuracy = if (d.accuracy > 0f) {
            getString(R.string.pm_info_accuracy_value, d.accuracy)
        } else {
            getString(R.string.not_available)
        }
        val altitude = if (d.alt != 0.0) {
            String.format(Locale.US, "%.1f m", d.alt)
        } else {
            getString(R.string.not_available)
        }
        val band = d.band.ifBlank { getString(R.string.not_available) }
        populateInfoRows(
            b.infoLocationRows,
            listOf(
                R.string.pm_info_coordinates to coords,
                R.string.pm_info_accuracy to accuracy,
                R.string.pm_info_altitude to altitude,
                R.string.pm_info_band to band
            )
        )

        populateInfoRows(
            b.infoObservationRows,
            listOf(
                R.string.pm_info_measures to numberFormat.format(d.measureCount),
                R.string.pm_info_first_seen to formatInfoTime(d.firstSeen),
                R.string.pm_info_last_seen to formatInfoTime(d.lastSeen),
                R.string.pm_info_source to sourceInfoLabel(d.source),
                R.string.pm_info_vendor to d.vendor.ifBlank { getString(R.string.not_available) }
            )
        )

        b.infoCross.text = checkedStatusText(
            when (d.otherDbState) {
                PersonalMapDbHelper.STATE_PRESENT -> getString(R.string.pm_info_cross_present)
                PersonalMapDbHelper.STATE_ABSENT -> getString(R.string.pm_info_cross_absent)
                else -> getString(R.string.pm_info_unknown)
            },
            d.crossCheckedAt
        )

        if (d.wpasecState == PersonalMapDbHelper.STATE_PRESENT) {
            b.infoWpasec.text =
                infoRow(R.string.pm_info_cross, getString(R.string.pm_info_wpasec_present))
            b.infoWpasec.setTextColor(
                ContextCompat.getColor(b.root.context, R.color.signal_good)
            )
        } else {
            b.infoWpasec.text = checkedStatusText(
                when (d.wpasecState) {
                    PersonalMapDbHelper.STATE_ABSENT -> getString(R.string.pm_info_wpasec_absent)
                    else -> getString(R.string.pm_info_unknown)
                },
                d.wpasecCheckedAt
            )
            b.infoWpasec.setTextColor(defaultStatusColor)
        }
    }

    private fun checkedStatusText(status: String, checkedAt: Long): String = buildString {
        append(infoRow(R.string.pm_info_cross, status))
        if (checkedAt > 0L) {
            append("\n")
            append(infoRow(R.string.pm_info_checked, formatInfoTime(checkedAt)))
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val ctx = context ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        safeSnackbar(R.string.pm_copied)
    }

    private fun showMatchDetailDialog(match: DbMatchEntry) {
        val ctx = context ?: return
        val sheet = BottomSheetDialog(ctx)
        val sheetBinding = BottomSheetDbMatchBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        sheetBinding.matchHeaderTitle.text =
            match.ssid.ifBlank { getString(R.string.unknown_ssid) }
        sheetBinding.matchHeaderSubtitle.text = match.databaseName.ifBlank { match.source }
        if (match.color != 0) {
            sheetBinding.matchHeaderColor.backgroundTintList =
                android.content.res.ColorStateList.valueOf(match.color)
        }

        fun addField(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            val row = ItemMatchFieldBinding.inflate(
                layoutInflater, sheetBinding.matchFieldsContainer, false
            )
            row.fieldLabel.text = label
            row.fieldValue.text = value
            row.btnCopyField.setOnClickListener { copyToClipboard(label, value) }
            sheetBinding.matchFieldsContainer.addView(row.root)
        }

        addField(
            getString(R.string.source_label),
            match.databaseName.ifBlank { match.source }
        )
        addField(getString(R.string.pm_info_ssid_row), match.ssid.ifBlank { getString(R.string.unknown_ssid) })
        addField(getString(R.string.pm_info_bssid_row), match.bssid)
        addField(getString(R.string.pm_match_password), match.password)
        addField(getString(R.string.pm_match_wps), match.wps)

        if (match.extra.isNotBlank()) {
            match.extra.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        addField(
                            line.substring(0, separator).trim(),
                            line.substring(separator + 1).trim()
                        )
                    } else {
                        addField(line, line)
                    }
                }
        }

        sheetBinding.btnCopyAllMatch.setOnClickListener {
            val text = buildString {
                appendLine(match.ssid)
                appendLine(match.bssid)
                if (!match.password.isNullOrBlank()) appendLine(match.password)
                if (!match.wps.isNullOrBlank()) appendLine(match.wps)
                if (match.extra.isNotBlank()) appendLine(match.extra)
            }.trim()
            copyToClipboard("match", text)
        }

        sheet.show()
    }

    private fun showApiResultDialog(found: Boolean, message: String) {
        val ctx = context ?: return
        val title = if (found) R.string.pm_match_found else R.string.pm_match_not_found
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage("${getString(R.string.pm_api_response)}:\n$message")
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.pm_copy) { _, _ ->
                copyToClipboard("api", message)
            }
            .show()
    }

    private fun infoRow(labelRes: Int, value: String): String =
        getString(R.string.pm_info_row, getString(labelRes), value)

    private fun populateInfoRows(
        container: LinearLayout,
        rows: List<Pair<Int, String>>
    ) {
        container.removeAllViews()
        rows.forEach { (labelRes, value) ->
            val row = ItemInfoRowBinding.inflate(layoutInflater, container, false)
            row.rowLabel.setText(labelRes)
            row.rowValue.text = value
            container.addView(row.root)
        }
    }

    private fun formatInfoTime(value: Long): String =
        PersonalMapUtils.formatDateTime(value, resources)

    private fun sourceInfoLabel(source: String): String =
        PersonalMapUtils.sourceLabel(source, resources)

    private fun securityInfoLabel(type: String?): String =
        PersonalMapUtils.securityLabel(type, resources)

    private fun showEditNameDialog(entry: PersonalMapLogEntry) {
        val dialogBinding = DialogEditPersonalPointBinding.inflate(layoutInflater)
        dialogBinding.editSsid.setText(entry.ssid)
        dialogBinding.editSsid.setSelection(dialogBinding.editSsid.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pm_edit_ssid_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val newName = dialogBinding.editSsid.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty()) {
                    dialogBinding.editSsid.error = getString(R.string.pm_edit_ssid_empty)
                    return@setOnClickListener
                }
                dialog.dismiss()
                viewModel.updateNetworkName(entry.id, newName) { ok ->
                    safeSnackbar(if (ok) R.string.pm_edit_ssid_done else R.string.pm_edit_ssid_failed)
                }
            }
        }
        dialog.show()
    }

    private fun updateRecordsEmpty(isEmpty: Boolean, searching: Boolean) {
        if (!isEmpty) {
            binding.pmRecordsEmpty.visibility = View.GONE
            return
        }
        binding.pmRecordsEmpty.visibility = View.VISIBLE
        binding.pmRecordsEmpty.text = getString(
            if (searching) R.string.pm_records_no_match else R.string.pm_records_empty
        )
    }

    private fun safeSnackbar(resId: Int) {
        val v = _binding?.root ?: return
        Snackbar.make(v, resId, Snackbar.LENGTH_SHORT).show()
    }

    private fun safeSnackbar(text: String) {
        val v = _binding?.root ?: return
        Snackbar.make(v, text, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.pmRecordsRecycler.adapter = null
        _binding = null
    }

    companion object {
        private const val SCROLL_LOAD_THRESHOLD = 20
    }
}
