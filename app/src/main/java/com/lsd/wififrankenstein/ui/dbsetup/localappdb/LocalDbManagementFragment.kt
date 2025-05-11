package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WpsGeneratorActivity
import com.lsd.wififrankenstein.databinding.FragmentLocalDbManagementBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LocalDbManagementFragment : Fragment() {

    private var isMultiSelectMode = false

    private var _binding: FragmentLocalDbManagementBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LocalDbManagementViewModel by viewModels()
    private lateinit var adapter: LocalDbManagementAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLocalDbManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupAddButton()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = LocalDbManagementAdapter(
            onItemClick = { item ->
                if (isMultiSelectMode) {
                    adapter.toggleSelection(item)
                } else {
                    showItemOptionsDialog(item)
                }
            },
            onItemLongClick = { item ->
                if (!isMultiSelectMode) {
                    isMultiSelectMode = true
                    adapter.toggleSelection(item)
                    showMultiSelectActionMode()
                }
            }
        )
        binding.recyclerViewLocalDb.adapter = adapter
    }

    private fun showMultiSelectActionMode() {
        (activity as? AppCompatActivity)?.startSupportActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                mode.menuInflater.inflate(R.menu.menu_multi_select, menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return when (item.itemId) {
                    R.id.action_delete -> {
                        deleteSelectedItems()
                        mode.finish()
                        true
                    }
                    else -> false
                }
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                isMultiSelectMode = false
                adapter.clearSelection()
            }
        })
    }

    private fun deleteSelectedItems() {
        val selectedItems = adapter.getSelectedItems()
        viewModel.deleteRecords(selectedItems)
        reloadFragment()
        Snackbar.make(binding.root, getString(R.string.records_deleted, selectedItems.size), Snackbar.LENGTH_LONG).show()
    }

    private fun setupAddButton() {
        binding.fabAddRecord.setOnClickListener {
            showAddRecordDialog()
        }
    }

    private fun reloadFragment() {
        findNavController().run {
            popBackStack()
            navigate(R.id.localDbManagementFragment)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.localDbItems.collectLatest { pagingData ->
                adapter.submitData(pagingData)
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun showItemOptionsDialog(item: WifiNetwork) {
        val options = mutableListOf<String>()
        options.add(getString(R.string.edit_record))
        options.add(getString(R.string.delete_record))
        options.add(getString(R.string.generate_wps_pins))
        if (item.wifiName.isNotBlank()) options.add(getString(R.string.copy_ssid))
        if (item.macAddress.isNotBlank()) options.add(getString(R.string.copy_bssid))
        if (!item.wifiPassword.isNullOrBlank()) options.add(getString(R.string.copy_password))
        if (!item.wpsCode.isNullOrBlank()) options.add(getString(R.string.copy_wps_pin))
        if (!item.adminPanel.isNullOrBlank()) options.add(getString(R.string.copy_admin_panel))
        if (item.latitude != null && item.longitude != null && item.latitude != 0.0 && item.longitude != 0.0) {
            options.add(getString(R.string.show_on_map))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.choose_action))
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    getString(R.string.edit_record) -> showEditRecordDialog(item)
                    getString(R.string.delete_record) -> deleteRecord(item)
                    getString(R.string.generate_wps_pins) -> navigateToWpsGenerator(item.macAddress)
                    getString(R.string.copy_ssid) -> copyToClipboard()
                    getString(R.string.copy_bssid) -> copyToClipboard()
                    getString(R.string.copy_password) -> copyToClipboard()
                    getString(R.string.copy_wps_pin) -> copyToClipboard()
                    getString(R.string.copy_admin_panel) -> copyToClipboard()
                    getString(R.string.show_on_map) -> showOnMap(item)
                }
            }
            .show()
    }

    private fun showOnMap(item: WifiNetwork) {
        val uri =
            "geo:${item.latitude},${item.longitude}?q=${item.latitude},${item.longitude}(${item.wifiName})".toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri)
        startActivity(intent)
    }

    private fun navigateToWpsGenerator(bssid: String) {
        val intent = Intent(requireContext(), WpsGeneratorActivity::class.java).apply {
            putExtra("BSSID", bssid)
        }
        startActivity(intent)
    }

    private fun showAddRecordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_record, null)
        val etWifiName = dialogView.findViewById<TextInputEditText>(R.id.etWifiName)
        val etMacAddress = dialogView.findViewById<TextInputEditText>(R.id.etMacAddress)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.etPassword)
        val etWpsPin = dialogView.findViewById<TextInputEditText>(R.id.etWpsPin)
        val etAdminPanel = dialogView.findViewById<TextInputEditText>(R.id.etAdminPanel)
        val etLatitude = dialogView.findViewById<TextInputEditText>(R.id.etLatitude)
        val etLongitude = dialogView.findViewById<TextInputEditText>(R.id.etLongitude)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_new_record)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val newRecord = WifiNetwork(
                    id = 0,
                    wifiName = etWifiName.text.toString(),
                    macAddress = etMacAddress.text.toString(),
                    wifiPassword = etPassword.text.toString().takeIf { it.isNotBlank() },
                    wpsCode = etWpsPin.text.toString().takeIf { it.isNotBlank() },
                    adminPanel = etAdminPanel.text.toString().takeIf { it.isNotBlank() },
                    latitude = etLatitude.text.toString().toDoubleOrNull(),
                    longitude = etLongitude.text.toString().toDoubleOrNull()
                )
                viewModel.addRecord(newRecord)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditRecordDialog(item: WifiNetwork) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_record, null)
        val etWifiName = dialogView.findViewById<TextInputEditText>(R.id.etWifiName)
        val etMacAddress = dialogView.findViewById<TextInputEditText>(R.id.etMacAddress)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.etPassword)
        val etWpsPin = dialogView.findViewById<TextInputEditText>(R.id.etWpsPin)
        val etAdminPanel = dialogView.findViewById<TextInputEditText>(R.id.etAdminPanel)
        val etLatitude = dialogView.findViewById<TextInputEditText>(R.id.etLatitude)
        val etLongitude = dialogView.findViewById<TextInputEditText>(R.id.etLongitude)

        etWifiName.setText(item.wifiName)
        etMacAddress.setText(item.macAddress)
        etPassword.setText(item.wifiPassword)
        etWpsPin.setText(item.wpsCode)
        etAdminPanel.setText(item.adminPanel)
        etLatitude.setText(item.latitude?.toString() ?: "")
        etLongitude.setText(item.longitude?.toString() ?: "")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_record)
            .setView(dialogView)
            .setPositiveButton(R.string.update) { _, _ ->
                val updatedRecord = item.copy(
                    wifiName = etWifiName.text.toString(),
                    macAddress = etMacAddress.text.toString(),
                    wifiPassword = etPassword.text.toString().takeIf { it.isNotBlank() },
                    wpsCode = etWpsPin.text.toString().takeIf { it.isNotBlank() },
                    adminPanel = etAdminPanel.text.toString().takeIf { it.isNotBlank() },
                    latitude = etLatitude.text.toString().toDoubleOrNull(),
                    longitude = etLongitude.text.toString().toDoubleOrNull()
                )
                viewModel.updateRecord(updatedRecord)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard() {
        Snackbar.make(binding.root, getString(R.string.copied_to_clipboard), Snackbar.LENGTH_SHORT).show()
    }

    private fun deleteRecord(item: WifiNetwork) {
        viewModel.deleteRecord(item)
        reloadFragment()
        Snackbar.make(binding.root, getString(R.string.record_deleted), Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}