package com.lsd.wififrankenstein.ui.uploadrouterscan

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentUploadRouterscanBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.settings.SettingsViewModel

class UploadRouterScanFragment : Fragment() {

    private var _binding: FragmentUploadRouterscanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UploadRouterScanViewModel by viewModels()
    private var serverAdapter: ArrayAdapter<String>? = null
    private var servers: List<DbItem> = emptyList()

    private val settingsViewModel: SettingsViewModel by viewModels()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.setSelectedFile(uri, requireContext().contentResolver)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUploadRouterscanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.buttonSelectFile.setOnClickListener {
            openFilePicker()
        }

        binding.buttonUpload.setOnClickListener {
            performUpload()
        }

        binding.buttonAddServer.setOnClickListener {
            showAddServerDialog()
        }

        binding.buttonUploadManual.setOnClickListener {
            performManualUpload()
        }

        setupModeToggle()

        serverAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            mutableListOf<String>()
        )
        serverAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerServer.adapter = serverAdapter
    }

    private fun observeViewModel() {
        viewModel.servers.observe(viewLifecycleOwner) { serverList ->
            servers = serverList
            updateServerSpinner()
        }

        viewModel.selectedFile.observe(viewLifecycleOwner) { file ->
            if (file != null) {
                binding.textViewSelectedFile.text = file.name
                binding.textViewFileSize.text = getString(
                    R.string.file_size_format,
                    file.size / (1024.0 * 1024.0)
                )
                binding.textViewSelectedFile.visibility = View.VISIBLE
                binding.textViewFileSize.visibility = View.VISIBLE
                updateUploadButtonState()
            } else {
                binding.textViewSelectedFile.visibility = View.GONE
                binding.textViewFileSize.visibility = View.GONE
                updateUploadButtonState()
            }
        }

        settingsViewModel.showAdvancedUploadOptions.observe(viewLifecycleOwner) { showAdvanced ->
            binding.layoutAdvancedOptions.visibility = if (showAdvanced) View.VISIBLE else View.GONE
            if (!showAdvanced) {
                binding.checkBoxNoWait.isChecked = false
            }
        }

        viewModel.isUploading.observe(viewLifecycleOwner) { isUploading ->
            binding.buttonUpload.isEnabled = !isUploading && canUpload()
            binding.buttonUploadManual.isEnabled = !isUploading
            binding.buttonSelectFile.isEnabled = !isUploading
            binding.buttonAddServer.isEnabled = !isUploading
            binding.spinnerServer.isEnabled = !isUploading
            binding.editTextComment.isEnabled = !isUploading
            binding.checkBoxExisting.isEnabled = !isUploading
            binding.checkBoxNoWait.isEnabled = !isUploading
            binding.editTextEssid.isEnabled = !isUploading
            binding.editTextBssid.isEnabled = !isUploading
            binding.editTextPassword.isEnabled = !isUploading
            binding.editTextWpsPin.isEnabled = !isUploading
            binding.editTextIp.isEnabled = !isUploading
            binding.editTextPort.isEnabled = !isUploading
            binding.editTextAuth.isEnabled = !isUploading
            binding.editTextSec.isEnabled = !isUploading
            binding.editTextLat.isEnabled = !isUploading
            binding.editTextLon.isEnabled = !isUploading
            binding.editTextTitle.isEnabled = !isUploading

            if (isUploading) {
                binding.progressUpload.visibility = View.VISIBLE
                binding.textViewProgress.visibility = View.VISIBLE
                binding.textViewProgress.text = getString(R.string.uploading)
            } else {
                binding.progressUpload.visibility = View.GONE
                binding.textViewProgress.visibility = View.GONE
            }
        }

        viewModel.uploadProgress.observe(viewLifecycleOwner) { progress ->
            binding.progressUpload.progress = progress
            binding.textViewProgress.text = getString(R.string.upload_progress, progress)
        }

        viewModel.uploadResult.observe(viewLifecycleOwner) { result ->
            result?.let {
                binding.textViewResult.visibility = View.VISIBLE
                binding.textViewResult.text = it.message
                binding.textViewResult.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (it.success) R.color.success_green else R.color.error_red
                    )
                )

                Toast.makeText(
                    requireContext(),
                    it.message,
                    if (it.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateServerSpinner() {
        val serverNames = if (servers.isEmpty()) {
            binding.textViewNoServers.visibility = View.VISIBLE
            binding.spinnerServer.visibility = View.GONE
            emptyList()
        } else {
            binding.textViewNoServers.visibility = View.GONE
            binding.spinnerServer.visibility = View.VISIBLE
            servers.map { server ->
                when {
                    server.userNick != null -> "${server.userNick} (${server.path})"
                    !server.apiWriteKey.isNullOrBlank() -> "${server.path} (${getString(R.string.authenticated_upload)})"
                    else -> "${server.path} (${getString(R.string.anonymous_upload)})"
                }
            }
        }

        serverAdapter?.clear()
        serverAdapter?.addAll(serverNames)
        serverAdapter?.notifyDataSetChanged()

        updateUploadButtonState()
    }

    private fun updateUploadButtonState() {
        binding.buttonUpload.isEnabled = canUpload() &&
                (viewModel.isUploading.value != true)
    }

    private fun canUpload(): Boolean {
        return viewModel.selectedFile.value != null &&
                servers.isNotEmpty() &&
                binding.spinnerServer.selectedItemPosition >= 0
    }

    private fun setupModeToggle() {
        binding.toggleMode.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val isFileMode = checkedId == R.id.buttonModeFile
            binding.layoutFileMode.visibility = if (isFileMode) View.VISIBLE else View.GONE
            binding.layoutManualMode.visibility = if (isFileMode) View.GONE else View.VISIBLE
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/plain"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerLauncher.launch(
            Intent.createChooser(
                intent,
                getString(R.string.select_routerscan_file)
            )
        )
    }

    private fun performUpload() {
        val selectedPosition = binding.spinnerServer.selectedItemPosition
        if (selectedPosition < 0 || selectedPosition >= servers.size) {
            Toast.makeText(
                requireContext(),
                getString(R.string.select_server_first),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val server = servers[selectedPosition]
        val comment = binding.editTextComment.text?.toString()?.trim() ?: ""
        val checkExisting = binding.checkBoxExisting.isChecked
        val noWait = binding.checkBoxNoWait.isChecked

        binding.textViewResult.visibility = View.GONE
        viewModel.uploadFile(server, comment, checkExisting, noWait)
    }

    private fun showAddServerDialog() {
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val urlInput = com.google.android.material.textfield.TextInputLayout(
            requireContext(),
            null,
            com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox
        ).apply {
            hint = getString(R.string.server_url_hint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            addView(TextInputEditText(requireContext()))
        }
        val keyInput = com.google.android.material.textfield.TextInputLayout(
            requireContext(),
            null,
            com.google.android.material.R.style.Widget_MaterialComponents_TextInputLayout_OutlinedBox
        ).apply {
            hint = getString(R.string.api_write_key_hint)
            addView(TextInputEditText(requireContext()))
        }
        wrapper.addView(urlInput)
        wrapper.addView(keyInput)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_server)
            .setView(wrapper)
            .setPositiveButton(R.string.add) { _, _ ->
                val url = (urlInput.editText?.text?.toString()?.trim() ?: "")
                if (url.isBlank()) {
                    Toast.makeText(requireContext(), R.string.db_invalid_url, Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                val key = keyInput.editText?.text?.toString()?.trim() ?: ""
                viewModel.addServer(url, key) { success, msg ->
                    Toast.makeText(
                        requireContext(), msg,
                        if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                    if (success) {
                        binding.spinnerServer.setSelection(0)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performManualUpload() {
        val essid = binding.editTextEssid.text?.toString()?.trim() ?: ""
        val bssid = binding.editTextBssid.text?.toString()?.trim() ?: ""

        if (essid.isEmpty() && bssid.isEmpty()) {
            Toast.makeText(requireContext(), R.string.fill_required_fields, Toast.LENGTH_SHORT)
                .show()
            return
        }

        val selectedPosition = binding.spinnerServer.selectedItemPosition
        if (selectedPosition < 0 || selectedPosition >= servers.size) {
            Toast.makeText(requireContext(), R.string.select_server_first, Toast.LENGTH_SHORT)
                .show()
            return
        }

        val server = servers[selectedPosition]
        val password = binding.editTextPassword.text?.toString()?.trim() ?: ""
        val wpsPin = binding.editTextWpsPin.text?.toString()?.trim() ?: ""
        val ip = binding.editTextIp.text?.toString()?.trim() ?: ""
        val portStr = binding.editTextPort.text?.toString()?.trim()?.ifEmpty { "80" } ?: "80"
        val port = portStr.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            Toast.makeText(requireContext(), getString(R.string.upl_invalid_port), Toast.LENGTH_SHORT).show()
            return
        }
        val auth = binding.editTextAuth.text?.toString()?.trim() ?: ""
        val sec = binding.editTextSec.text?.toString()?.trim() ?: ""
        val title = binding.editTextTitle.text?.toString()?.trim() ?: ""
        val latText = binding.editTextLat.text?.toString()?.trim() ?: ""
        val lonText = binding.editTextLon.text?.toString()?.trim() ?: ""
        if (latText.isNotEmpty() && latText.toDoubleOrNull() == null) {
            Toast.makeText(requireContext(), getString(R.string.upl_invalid_latitude), Toast.LENGTH_SHORT).show()
            return
        }
        if (lonText.isNotEmpty() && lonText.toDoubleOrNull() == null) {
            Toast.makeText(requireContext(), getString(R.string.upl_invalid_longitude), Toast.LENGTH_SHORT).show()
            return
        }
        if (wpsPin.isNotEmpty() && (wpsPin.length != 8 || !wpsPin.all { it.isDigit() })) {
            Toast.makeText(requireContext(), getString(R.string.upl_wps_pin_8_digits), Toast.LENGTH_SHORT).show()
            return
        }
        val comment = binding.editTextComment.text?.toString()?.trim() ?: ""

        binding.textViewResult.visibility = View.GONE
        viewModel.uploadManualData(
            server,
            essid,
            bssid,
            password,
            wpsPin,
            ip,
            portStr,
            auth,
            sec,
            title,
            latText,
            lonText,
            comment
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}