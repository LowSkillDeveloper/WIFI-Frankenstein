package com.lsd.wififrankenstein.ui.handshakecapture

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.BottomSheetImportHandshakeBinding

class ImportHandshakeBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetImportHandshakeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HandshakeStorageViewModel by viewModels(ownerProducer = { requireActivity() })

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            Toast.makeText(
                requireContext(),
                R.string.handshake_share_generating,
                Toast.LENGTH_SHORT
            ).show()
            viewModel.importFromUri(it) { result -> showImportResult(result) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetImportHandshakeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnImportFile.setOnClickListener {
            dismiss()
            filePicker.launch(arrayOf("*/*"))
        }

        binding.btnImportUrl.setOnClickListener {
            showUrlInputDialog()
        }

        binding.btnImportText.setOnClickListener {
            showTextInputDialog()
        }
    }

    private fun showUrlInputDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.handshake_import_url_hint)
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_import_url_title)
            .setView(input)
            .setPositiveButton(R.string.handshake_import_url_button) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.handshake_import_url_empty,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                val isMega = url.contains(
                    "mega",
                    ignoreCase = true
                ) || url.contains("#F!") || url.startsWith("mega://")
                Toast.makeText(
                    requireContext(),
                    R.string.handshake_share_generating,
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.importFromUrl(url, isMega) { result -> showImportResult(result) }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showTextInputDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.handshake_import_text_hint)
            gravity = Gravity.START or Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(48, 32, 48, 32)
            minLines = 6
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_import_text_title)
            .setMessage(R.string.handshake_import_text_message)
            .setView(input)
            .setPositiveButton(R.string.handshake_import_text_button) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.handshake_import_text_empty,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                Toast.makeText(
                    requireContext(),
                    R.string.handshake_share_generating,
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.importFromText(text) { result -> showImportResult(result) }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showImportResult(result: HandshakeImportManager.ImportResult) {
        val msg = buildString {
            appendLine(
                getString(
                    R.string.handshake_import_result_format,
                    result.successCount,
                    result.failCount
                )
            )
            if (result.warnings.isNotEmpty()) {
                appendLine()
                result.warnings.forEach { appendLine("- $it") }
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_import_result_title)
            .setMessage(msg.trimEnd())
            .setPositiveButton(R.string.close, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
