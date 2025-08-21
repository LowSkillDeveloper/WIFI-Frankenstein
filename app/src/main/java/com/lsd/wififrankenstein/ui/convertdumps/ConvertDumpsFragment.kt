package com.lsd.wififrankenstein.ui.convertdumps

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentConvertDumpsBinding
import com.lsd.wififrankenstein.service.ConversionService
import com.lsd.wififrankenstein.util.Log
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

class ConvertDumpsFragment : Fragment() {

    private var _binding: FragmentConvertDumpsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ConvertDumpsViewModel by viewModels()
    private lateinit var txtFilesAdapter: SelectedFilesAdapter

    private val multipleFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = mutableListOf<Uri>()
            result.data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } ?: result.data?.data?.let { uri ->
                uris.add(uri)
            }
            viewModel.addRouterScanFiles(uris)
        }
    }

    private val outputLocationPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setOutputLocation(uri)
            }
        }
    }

    private val singleFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                when (currentFileSelection) {
                    FileSelectionType.BASE_FILE -> viewModel.setBaseFile(uri)
                    FileSelectionType.GEO_FILE -> viewModel.setGeoFile(uri)
                    FileSelectionType.INPUT_FILE -> viewModel.setInputFile(uri)
                    else -> {}
                }
            }
        }
    }

    private var currentFileSelection = FileSelectionType.NONE

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            proceedWithConversion()
        } else {
            showSnackbar(getString(R.string.notification_permission_denied))
            proceedWithConversion()
        }
    }

    private enum class FileSelectionType {
        NONE, BASE_FILE, GEO_FILE, INPUT_FILE
    }

    private val conversionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("ConvertDumpsFragment", "Received broadcast: ${intent?.action}")

            when (intent?.action) {
                ConversionService.BROADCAST_CONVERSION_STATUS -> {
                    val isConverting = intent.getBooleanExtra(ConversionService.EXTRA_IS_CONVERTING, false)
                    if (!isConverting && viewModel.isConverting.value == true) {
                        viewModel.conversionComplete("")
                    }
                }
                ConversionService.BROADCAST_CONVERSION_PROGRESS -> {
                    val progress = intent.getIntExtra(ConversionService.EXTRA_PROGRESS, 0)
                    val fileName = intent.getStringExtra(ConversionService.EXTRA_FILE_NAME) ?: ""
                    Log.d("ConvertDumpsFragment", "Progress: $fileName - $progress%")
                    viewModel.updateProgress(fileName, progress)
                }
                ConversionService.BROADCAST_CONVERSION_COMPLETE -> {
                    val outputFile = intent.getStringExtra(ConversionService.EXTRA_OUTPUT_FILE) ?: ""
                    Log.d("ConvertDumpsFragment", "Conversion complete: $outputFile")
                    viewModel.conversionComplete(outputFile)
                    showSnackbar("${getString(R.string.conversion_complete)}\n${getString(R.string.output_database, outputFile)}")
                }
                ConversionService.BROADCAST_CONVERSION_ERROR -> {
                    val error = intent.getStringExtra(ConversionService.EXTRA_ERROR_MESSAGE) ?: ""
                    Log.e("ConvertDumpsFragment", "Conversion error: $error")
                    viewModel.conversionError(error)
                    showSnackbar("${getString(R.string.conversion_error)}: $error")
                }
                ConversionService.BROADCAST_CONVERSION_CANCELLED -> {
                    Log.d("ConvertDumpsFragment", "Conversion cancelled")
                    viewModel.conversionCancelled()
                    showSnackbar(getString(R.string.conversion_cancelled))
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConvertDumpsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupObservers()
        setupListeners()
    }

    private fun setupRecyclerView() {
        txtFilesAdapter = SelectedFilesAdapter { uri ->
            viewModel.removeRouterScanFile(uri)
        }
        binding.recyclerTxtFiles.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = txtFilesAdapter
        }
    }

    private fun setupObservers() {
        viewModel.conversionType.observe(viewLifecycleOwner) { type ->
            updateVisibilityForType(type)
        }

        viewModel.routerScanFiles.observe(viewLifecycleOwner) { files ->
            txtFilesAdapter.submitList(files)
            binding.textTxtFilesStatus.text = if (files.isEmpty()) {
                getString(R.string.txt_files_required)
            } else {
                getString(R.string.files_selected, files.size)
            }
        }

        viewModel.outputLocationText.observe(viewLifecycleOwner) { text ->
            binding.textOutputLocationStatus.text = if (text.isNotEmpty()) {
                text
            } else {
                getString(R.string.output_location_internal)
            }
        }

        viewModel.outputFileName.observe(viewLifecycleOwner) { fileName ->
            if (fileName.isNotEmpty()) {
                binding.textOutputFileName.text = fileName
                binding.textOutputFileName.visibility = View.VISIBLE
            } else {
                binding.textOutputFileName.visibility = View.GONE
            }
        }

        viewModel.baseFile.observe(viewLifecycleOwner) { file ->
            binding.textBaseFileStatus.text = file?.let {
                getString(R.string.file_selected, it.name)
            } ?: getString(R.string.base_file_required)
        }

        viewModel.geoFile.observe(viewLifecycleOwner) { file ->
            binding.textGeoFileStatus.text = file?.let {
                getString(R.string.file_selected, it.name)
            } ?: getString(R.string.geo_file_required)
        }

        viewModel.inputFile.observe(viewLifecycleOwner) { file ->
            binding.textInputFileStatus.text = file?.let {
                getString(R.string.file_selected, it.name)
            } ?: getString(R.string.input_file_required)
        }

        viewModel.outputLocation.observe(viewLifecycleOwner) { location ->
            binding.textOutputLocationStatus.text = if (location != null) {
                getString(R.string.output_location_custom)
            } else {
                getString(R.string.output_location_internal)
            }
        }

        viewModel.canStartConversion.observe(viewLifecycleOwner) { canStart ->
            binding.buttonStartConversion.isEnabled = canStart
        }

        viewModel.isConverting.observe(viewLifecycleOwner) { isConverting ->
            setUIEnabled(!isConverting)

            if (isConverting) {
                binding.layoutProgress.visibility = View.VISIBLE
                binding.animatedLoadingBar.startAnimation()
                binding.textProgressStatus.text = getString(R.string.conversion_in_progress)
                binding.textProgressPercentage.text = "0%"
            } else {
                binding.animatedLoadingBar.stopAnimation()
                binding.layoutProgress.visibility = View.GONE
            }
        }

        viewModel.conversionProgress.observe(viewLifecycleOwner) { progressMap ->
            if (progressMap.isNotEmpty()) {
                val latestProgress = progressMap.values.maxOrNull() ?: 0
                val fileName = progressMap.keys.lastOrNull() ?: ""

                binding.textProgressStatus.text = getString(R.string.processing_file, fileName, latestProgress)
                binding.textProgressPercentage.text = "${latestProgress}%"

                if (latestProgress >= 100) {
                    binding.animatedLoadingBar.stopAnimation()
                }
            }
        }
    }

    private fun setupListeners() {
        binding.radioGroupConversionType.setOnCheckedChangeListener { _, checkedId ->
            val type = when (checkedId) {
                R.id.radio_routerscan -> ConversionType.ROUTERSCAN_TXT
                R.id.radio_3wifi -> ConversionType.WIFI_3_SQL
                R.id.radio_p3wifi -> ConversionType.P3WIFI_SQL
                else -> null
            }
            type?.let { viewModel.setConversionType(it) }
        }

        binding.switchOptimization.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setOptimizationEnabled(isChecked)
        }

        binding.buttonSelectTxtFiles.setOnClickListener {
            openMultipleFilePicker()
        }

        binding.buttonSelectBaseFile.setOnClickListener {
            currentFileSelection = FileSelectionType.BASE_FILE
            openSingleFilePicker()
        }

        binding.buttonSelectGeoFile.setOnClickListener {
            currentFileSelection = FileSelectionType.GEO_FILE
            openSingleFilePicker()
        }

        binding.buttonSelectInputFile.setOnClickListener {
            currentFileSelection = FileSelectionType.INPUT_FILE
            openSingleFilePicker()
        }

        binding.buttonSelectOutputLocation.setOnClickListener {
            openOutputLocationPicker()
        }

        binding.buttonStartConversion.setOnClickListener {
            startConversion()
        }
    }

    private fun updateVisibilityForType(type: ConversionType?) {
        binding.cardRouterscanFiles.visibility = if (type == ConversionType.ROUTERSCAN_TXT) View.VISIBLE else View.GONE
        binding.card3wifiFiles.visibility = if (type == ConversionType.WIFI_3_SQL) View.VISIBLE else View.GONE
        binding.cardP3wifiFiles.visibility = if (type == ConversionType.P3WIFI_SQL) View.VISIBLE else View.GONE
    }

    private fun setUIEnabled(enabled: Boolean) {
        binding.radioGroupConversionType.isEnabled = enabled
        binding.buttonSelectTxtFiles.isEnabled = enabled
        binding.buttonSelectBaseFile.isEnabled = enabled
        binding.buttonSelectGeoFile.isEnabled = enabled
        binding.buttonSelectInputFile.isEnabled = enabled
        binding.buttonSelectOutputLocation.isEnabled = enabled
        binding.radioGroupMode.isEnabled = enabled
        binding.radioGroupIndexing.isEnabled = enabled
        binding.switchOptimization.isEnabled = enabled
    }

    private fun openMultipleFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        multipleFilePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.select_txt_files)))
    }


    private fun openOutputLocationPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        outputLocationPickerLauncher.launch(intent)
    }

    private fun openSingleFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        singleFilePickerLauncher.launch(Intent.createChooser(intent, getString(R.string.select_dump_files)))
    }

    private fun startConversion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    proceedWithConversion()
                }
                else -> {
                    showNotificationPermissionDialog()
                }
            }
        } else {
            proceedWithConversion()
        }
    }

    private fun showNotificationPermissionDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.notification_permission_title))
            .setMessage(getString(R.string.notification_permission_message))
            .setPositiveButton(getString(R.string.grant_permission)) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .setNegativeButton(getString(R.string.continue_without_permission)) { _, _ ->
                proceedWithConversion()
            }
            .setCancelable(false)
            .show()
    }

    private fun proceedWithConversion() {
        val files = viewModel.getAllSelectedFiles()
        Log.d("ConvertDumpsFragment", "Starting conversion with ${files.size} files")

        if (files.isEmpty()) {
            showSnackbar(getString(R.string.select_files_first))
            return
        }

        files.forEach { file ->
            Log.d("ConvertDumpsFragment", "File: ${file.name}, type: ${file.type}")
        }

        val mode = when (binding.radioGroupMode.checkedRadioButtonId) {
            R.id.radio_performance_mode -> ConversionMode.PERFORMANCE
            R.id.radio_economy_mode -> ConversionMode.ECONOMY
            else -> ConversionMode.ECONOMY
        }

        val indexing = when (binding.radioGroupIndexing.checkedRadioButtonId) {
            R.id.radio_full_indexing -> IndexingOption.FULL
            R.id.radio_basic_indexing -> IndexingOption.BASIC
            R.id.radio_no_indexing -> IndexingOption.NONE
            else -> IndexingOption.BASIC
        }

        Log.d("ConvertDumpsFragment", "Mode: $mode, Indexing: $indexing")

        viewModel.startConversion()

        Log.d("ConvertDumpsFragment", "Starting ConversionService...")
        ConversionService.startConversion(
            requireContext(),
            files,
            mode,
            indexing,
            viewModel.outputLocation.value,
            viewModel.optimizationEnabled.value ?: true
        )
        Log.d("ConvertDumpsFragment", "ConversionService.startConversion called")
    }

    private fun showSnackbar(message: String) {
        view?.let { v ->
            Snackbar.make(v, message, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        ConversionService.checkStatus(requireContext())
        val filter = IntentFilter().apply {
            addAction(ConversionService.BROADCAST_CONVERSION_STATUS)
            addAction(ConversionService.BROADCAST_CONVERSION_PROGRESS)
            addAction(ConversionService.BROADCAST_CONVERSION_COMPLETE)
            addAction(ConversionService.BROADCAST_CONVERSION_ERROR)
            addAction(ConversionService.BROADCAST_CONVERSION_CANCELLED)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(conversionReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(conversionReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}