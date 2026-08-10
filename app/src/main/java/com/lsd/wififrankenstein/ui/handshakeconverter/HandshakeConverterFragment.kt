package com.lsd.wififrankenstein.ui.handshakeconverter

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentHandshakeConverterBinding
import com.lsd.wififrankenstein.util.Log
import java.io.File
import java.util.ArrayDeque

class HandshakeConverterFragment : Fragment() {

    private var _binding: FragmentHandshakeConverterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HandshakeConverterViewModel by viewModels()

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.loadFiles(uris)
    }

    private val pendingSaves = ArrayDeque<ConvertResultItem>()
    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri == null) {
            pendingSaves.clear()
            return@registerForActivityResult
        }
        val item = pendingSaves.pollFirst() ?: return@registerForActivityResult
        saveOutput(item, uri)
        launchNextSave()
    }

    private lateinit var adapter: HandshakeConverterAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHandshakeConverterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = HandshakeConverterAdapter(
            onTargetSelected = { id, target -> viewModel.setTargetFormat(id, target) },
            onRemove = { id -> viewModel.removeFile(id) }
        )
        binding.recyclerFiles.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HandshakeConverterFragment.adapter
        }
        binding.buttonSelectFiles.setOnClickListener {
            filePicker.launch(arrayOf("*/*"))
        }
        binding.buttonConvert.setOnClickListener {
            viewModel.convertAll()
        }
        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.files.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
            updateUi()
        }

        viewModel.isConverting.observe(viewLifecycleOwner) {
            updateUi()
        }

        viewModel.results.observe(viewLifecycleOwner) { results ->
            if (results != null) {
                showResultsDialog(results)
                viewModel.clearResults()
            }
        }
    }

    private fun updateUi() {
        val items = viewModel.files.value.orEmpty()
        val converting = viewModel.isConverting.value == true
        val hasFiles = items.isNotEmpty()

        binding.textConverterCount.text = resources.getQuantityString(
            R.plurals.handshake_converter_files_count, items.size, items.size
        )
        binding.buttonConvert.visibility = if (hasFiles) View.VISIBLE else View.GONE
        binding.buttonConvert.isEnabled = hasFiles && !converting
        binding.buttonConvert.text = getString(
            if (converting) R.string.handshake_converter_converting
            else R.string.handshake_converter_convert
        )

        if (converting) {
            binding.progressConverting.visibility = View.VISIBLE
            binding.layoutEmpty.visibility = View.GONE
            binding.loadingContainer.visibility = View.VISIBLE
            binding.recyclerFiles.visibility = View.GONE
            binding.textConverterHint.visibility = View.GONE
        } else {
            binding.progressConverting.visibility = View.GONE
            binding.loadingContainer.visibility = if (hasFiles) View.GONE else View.VISIBLE
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.recyclerFiles.visibility = if (hasFiles) View.VISIBLE else View.GONE
            binding.textConverterHint.visibility = View.VISIBLE
        }
    }

    private fun showResultsDialog(results: List<ConvertResultItem>) {
        val density = resources.displayMetrics.density
        val dp8 = (8 * density).toInt()
        val dp12 = (12 * density).toInt()

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        val successCount = results.count { it.success }
        val failedCount = results.size - successCount
        content.addView(TextView(requireContext()).apply {
            text = getString(
                R.string.handshake_converter_result_summary, successCount, failedCount
            )
            textSize = 14f
            setPadding(dp12, dp12, dp12, dp8)
        })

        val accentColor = resolveAccentColor()
        for (result in results) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp12, dp8, dp12, dp8)
            }

            val statusText = if (result.success) {
                getString(
                    R.string.handshake_converter_result_ok,
                    result.sourceName, result.target.extension
                )
            } else {
                getString(
                    R.string.handshake_converter_result_failed,
                    result.sourceName, result.error ?: ""
                )
            }
            row.addView(TextView(requireContext()).apply {
                text = statusText
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })

            if (result.success) {
                row.addView(TextView(requireContext()).apply {
                    text = getString(R.string.handshake_converter_save)
                    textSize = 13f
                    setTextColor(accentColor)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    isClickable = true
                    isFocusable = true
                    setPadding(dp8, dp8, 0, dp8)
                    background = resolveRippleBackground()
                    setOnClickListener {
                        pendingSaves.clear()
                        pendingSaves.add(result)
                        launchNextSave()
                    }
                })
            }
            content.addView(row)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.handshake_converter_result_title)
            .setView(content)
            .setPositiveButton(R.string.handshake_converter_save_all) { _, _ ->
                pendingSaves.clear()
                pendingSaves.addAll(results.filter { it.success })
                launchNextSave()
            }
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.delete) { _, _ ->
                results.forEach { if (it.success) viewModel.deleteOutput(it.outputPath) }
            }
            .create()
        dialog.setOnDismissListener {
            results.forEach { if (it.success) viewModel.deleteOutput(it.outputPath) }
        }
        dialog.show()
    }

    private fun launchNextSave() {
        val next = pendingSaves.peekFirst() ?: return
        saveLauncher.launch(next.suggestedFileName)
    }

    private fun saveOutput(item: ConvertResultItem, uri: Uri) {
        val ctx = requireContext()
        try {
            val file = File(item.outputPath)
            if (!file.exists()) {
                Toast.makeText(
                    ctx, R.string.handshake_converter_save_failed, Toast.LENGTH_SHORT
                ).show()
                return
            }
            val opened = ctx.contentResolver.openOutputStream(uri)?.use { os ->
                file.inputStream().use { it.copyTo(os) }
            }
            if (opened == null) {
                Toast.makeText(
                    ctx, R.string.handshake_converter_save_failed, Toast.LENGTH_SHORT
                ).show()
                return
            }
            Toast.makeText(
                ctx, R.string.handshake_converter_saved, Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.w("HandshakeConvFrag", "save failed", e)
            Toast.makeText(
                ctx, R.string.handshake_converter_save_failed, Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun resolveRippleBackground(): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, tv, true
        )
        return if (tv.resourceId != 0) {
            ContextCompat.getDrawable(requireContext(), tv.resourceId)
        } else {
            null
        }
    }

    private fun resolveAccentColor(): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, tv, true)
        return if (tv.resourceId != 0) {
            ContextCompat.getColor(requireContext(), tv.resourceId)
        } else {
            tv.data
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
