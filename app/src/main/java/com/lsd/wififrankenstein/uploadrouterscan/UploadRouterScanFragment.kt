package com.lsd.wififrankenstein.ui.uploadrouterscan

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentUploadRouterscanBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbItem

class UploadRouterScanFragment : Fragment() {

    private var _binding: FragmentUploadRouterscanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UploadRouterScanViewModel by viewModels()
    private var serverAdapter: ArrayAdapter<String>? = null
    private var servers: List<DbItem> = emptyList()

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

        viewModel.isUploading.observe(viewLifecycleOwner) { isUploading ->
            binding.buttonUpload.isEnabled = !isUploading && canUpload()
            binding.buttonSelectFile.isEnabled = !isUploading
            binding.spinnerServer.isEnabled = !isUploading
            binding.editTextComment.isEnabled = !isUploading
            binding.checkBoxExisting.isEnabled = !isUploading
            binding.checkBoxNoWait.isEnabled = !isUploading

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

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/plain"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.select_routerscan_file)))
    }

    private fun performUpload() {
        val selectedPosition = binding.spinnerServer.selectedItemPosition
        if (selectedPosition < 0 || selectedPosition >= servers.size) {
            Toast.makeText(requireContext(), getString(R.string.select_server_first), Toast.LENGTH_SHORT).show()
            return
        }

        val server = servers[selectedPosition]
        val comment = binding.editTextComment.text?.toString()?.trim() ?: ""
        val checkExisting = binding.checkBoxExisting.isChecked
        val noWait = binding.checkBoxNoWait.isChecked

        binding.textViewResult.visibility = View.GONE
        viewModel.uploadFile(server, comment, checkExisting, noWait)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}