package com.lsd.wififrankenstein.ui.routerscan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.data.RouterScanResult
import com.lsd.wififrankenstein.databinding.FragmentRouterScanBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.pixiedust.ConsoleAdapter
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.RootlessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.commons.net.util.SubnetUtils

class RouterScanFragment : Fragment() {

    private var _binding: FragmentRouterScanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RouterScanViewModel by viewModels()
    private val dbSetupViewModel: DbSetupViewModel by viewModels()

    private val chrootManager by lazy { ChrootManager(requireContext()) }
    private val rootlessManager by lazy { RootlessManager(requireContext()) }

    private lateinit var resultAdapter: RouterScanAdapter
    private lateinit var consoleAdapter: ConsoleAdapter
    private var consoleVisible = false
    private var showFailed = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouterScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkChrootAndSetup()

        val targetIp = arguments?.getString("target_ip", "")
        if (!targetIp.isNullOrEmpty()) {
            binding.editTextIp.setText(targetIp)
        }
    }

    private fun checkChrootAndSetup() {
        val chrootType = chrootManager.getChrootType()
        when (chrootType) {
            com.lsd.wififrankenstein.util.ChrootType.None -> {
                showChrootPlaceholder(withInstall = false)
            }

            is com.lsd.wififrankenstein.util.ChrootType.RootMissing -> {
                showChrootPlaceholder(withInstall = true)
            }

            is com.lsd.wififrankenstein.util.ChrootType.Root -> {
                showScanInterface()
                setupRecyclerViews()
                setupButtons()
                observeViewModel()
                restoreState()
            }

            is com.lsd.wififrankenstein.util.ChrootType.RootWithoutChroot -> {
                showChrootPlaceholder(withInstall = true)
            }

            is com.lsd.wififrankenstein.util.ChrootType.Rootless -> {
                if (rootlessManager.isSetupCompleted()) {
                    showScanInterface()
                    setupRecyclerViews()
                    setupButtons()
                    observeViewModel()
                    restoreState()
                } else {
                    showChrootPlaceholder(withInstall = false)
                }
            }
        }
    }

    private fun showChrootPlaceholder(withInstall: Boolean) {
        binding.scrollContent.visibility = View.GONE
        binding.bottomProgressCard.visibility = View.GONE
        binding.placeholderChroot.visibility = View.VISIBLE

        if (withInstall) {
            binding.textViewChrootMessage.text = getString(R.string.chroot_not_installed)
            binding.buttonInstallChroot.text = getString(R.string.install_chroot)
            binding.buttonInstallChroot.setOnClickListener { startChrootInstallation() }
        } else {
            binding.textViewChrootMessage.text = getString(R.string.routerscan_root_required)
            binding.buttonInstallChroot.text = getString(R.string.go_back)
            binding.buttonInstallChroot.setOnClickListener {
                findNavController().navigateUp()
            }
        }
    }

    private fun showScanInterface() {
        binding.placeholderChroot.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE
        binding.bottomProgressCard.visibility = View.VISIBLE
    }

    private fun startChrootInstallation() {
        if (!chrootManager.isArmArchitecture()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Unsupported Architecture")
                .setMessage(
                    getString(
                        R.string.chroot_arch_warning,
                        chrootManager.getArchitecture().label
                    )
                )
                .setPositiveButton(R.string.continue_text) { _, _ -> internalStartChrootInstallation() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        internalStartChrootInstallation()
    }

    private fun internalStartChrootInstallation() {
        binding.progressBarChrootInstall.visibility = View.VISIBLE
        binding.textViewChrootStatus.visibility = View.VISIBLE
        binding.buttonInstallChroot.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val success = try {
                val result = chrootManager.downloadAndInstall(
                    onProgress = { progress ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            binding.textViewChrootStatus.text =
                                "${getString(R.string.chroot_installing)} $progress%"
                        }
                    },
                    onStatusUpdate = { status ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            binding.textViewChrootStatus.text = status
                        }
                    }
                )
                result
            } catch (e: Exception) {
                false
            }

            if (_binding == null) return@launch
            binding.progressBarChrootInstall.visibility = View.GONE
            binding.buttonInstallChroot.isEnabled = true

            if (success) {
                binding.textViewChrootStatus.text = getString(R.string.chroot_installed_success)
                checkChrootAndSetup()
            } else {

            }
        }
    }

    private fun setupRecyclerViews() {
        resultAdapter = RouterScanAdapter(
            { result ->
                val message = """
                    IP: ${result.ip}:${result.port}
                    SSID: ${result.ssid}
                    Auth: ${result.auth}
                    Sec: ${result.sec}
                    Key: ${result.psk}
                    WPS: ${result.wps}
                """.trimIndent()
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            },
            { result ->
                showFullOutputDialog(result)
            },
            { result ->
                uploadSingleTo3WiFi(result)
            }
        )
        binding.recyclerViewResults.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = resultAdapter
        }

        consoleAdapter = ConsoleAdapter(autoScroll = true)
        binding.recyclerViewConsole.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = consoleAdapter
        }
        consoleAdapter.attachToRecyclerView(binding.recyclerViewConsole)
    }

    private fun setupButtons() {
        binding.buttonScan.setOnClickListener {
            if (viewModel.state.value?.isScanning == true) {
                viewModel.cancelScan()
            } else {
                startScan()
            }
        }

        binding.buttonClear.setOnClickListener {
            viewModel.clearResults()
        }

        binding.buttonExport.setOnClickListener {
            exportToCsv()
        }

        binding.buttonUpload.setOnClickListener {
            uploadAllTo3WiFi()
        }

        binding.buttonSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.buttonSave.setOnClickListener {
            val ip = binding.editTextIp.text?.toString()?.trim() ?: ""
            val ports = binding.editTextPorts.text?.toString()?.trim() ?: "80"
            viewModel.saveState(ip, ports)
            Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
        }

        binding.buttonToggleConsole.setOnClickListener {
            toggleConsole()
        }

        binding.chipShowFailed.setOnClickListener {
            showFailed = !showFailed
            binding.chipShowFailed.isChecked = showFailed
            filterResults()
        }
    }

    private fun filterResults() {
        val state = viewModel.state.value ?: return
        val filtered = state.results.filter { result ->
            showFailed || result.type != 2
        }
        resultAdapter.submitList(filtered)
    }

    private fun startScan() {
        val ipInput = binding.editTextIp.text?.toString()?.trim() ?: ""
        val portsInput = binding.editTextPorts.text?.toString()?.trim()?.ifEmpty { "80" } ?: "80"

        viewModel.saveInputState(ipInput, portsInput)

        if (ipInput.isEmpty()) {
            Toast.makeText(requireContext(), "Enter IP address or range", Toast.LENGTH_SHORT).show()
            return
        }

        val ips = parseIpRange(ipInput)
        if (ips.isEmpty()) {
            Toast.makeText(requireContext(), "Invalid IP address or range", Toast.LENGTH_SHORT)
                .show()
            return
        }

        if (ips.size > 500) {
            Toast.makeText(requireContext(), "Range too large. Max 500 IPs.", Toast.LENGTH_LONG)
                .show()
            return
        }

        val ports = parsePorts(portsInput)
        if (ports.isEmpty()) {
            Toast.makeText(requireContext(), "Invalid port", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.scanAll(ips, ports)
    }

    private fun observeViewModel() {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            val isScanning = state.isScanning

            binding.buttonScan.text = if (isScanning) "Cancel" else "Start Scan"

            binding.progressBar.progress = state.rsCount.coerceAtMost(state.totalToScan)
            binding.progressBar.max = state.totalToScan
            binding.pingProgressBar.progress = state.pingCount.coerceAtMost(state.totalToScan)
            binding.pingProgressBar.max = state.totalToScan

            binding.textScanned.text = "Scanned: ${state.rsCount}/${state.totalToScan}"
            binding.textPing.text = "Ping: ${state.successfulPingCount}/${state.pingCount}"
            binding.textSuccess.text = "Success: ${state.successCount}"
            binding.textFailure.text = "Failed: ${state.failureCount}"

            if (state.error != null) {
                Toast.makeText(requireContext(), state.error, Toast.LENGTH_SHORT).show()
            }

            filterResults()
        }

        viewModel.consoleLines.observe(viewLifecycleOwner) { lines ->
            consoleAdapter.addLines(lines)
        }

        viewModel.scanComplete.observe(viewLifecycleOwner) { complete ->
            if (complete) {
                showScanCompleteDialog()
            }
        }

        viewModel.isUploading.observe(viewLifecycleOwner) { uploading ->
            binding.buttonUpload.isEnabled = !uploading
        }

        viewModel.uploadResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                if (result.success) {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseIpRange(input: String): List<String> {
        val ips = mutableListOf<String>()

        input.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line.contains("/") -> {
                    try {
                        val utils = SubnetUtils(line.replace(" ", ""))
                        utils.isInclusiveHostCount = false
                        val info = utils.info
                        info.getAllAddresses()?.toList()?.forEach { ips.add(it) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse CIDR: $line", e)
                    }
                }

                line.contains("-") -> {
                    val parts = line.split("-")
                    if (parts.size == 2) {
                        val start = parts[0].trim()
                        val end = parts[1].trim()
                        val startOctets = start.split(".")
                        val endOctets = end.split(".")
                        if (startOctets.size == 4 && endOctets.size == 4) {
                            val startPrefix = start.substring(0, start.lastIndexOf('.'))
                            val startLast = startOctets[3].toIntOrNull() ?: 1
                            val endLast = endOctets[3].toIntOrNull() ?: 254
                            (startLast..endLast).map { "$startPrefix.$it" }.forEach { ips.add(it) }
                        }
                    }
                }

                validateIp(line) -> ips.add(line)
            }
        }

        return ips.distinct()
    }

    private fun parsePorts(portsInput: String): List<String> {
        return portsInput.split("\n").map { it.trim() }.filter {
            it.isNotEmpty() && it.toIntOrNull() != null
        }.distinct()
    }

    private fun validateIp(ip: String): Boolean {
        return ip.matches(Regex("^((0|1\\d?\\d?|2[0-4]?\\d?|25[0-5]?|[3-9]\\d?)\\.){3}(0|1\\d?\\d?|2[0-4]?\\d?|25[0-5]?|[3-9]\\d?)$"))
    }

    private fun showSettingsDialog() {
        val settings = viewModel.getScanSettings()
        val dialogView = layoutInflater.inflate(R.layout.dialog_router_settings, null)

        val maxThreadsInput = dialogView.findViewById<TextInputLayout>(R.id.maxThreadsInput)
        val timeoutInput = dialogView.findViewById<TextInputLayout>(R.id.timeoutInput)
        val rsTimeoutInput = dialogView.findViewById<TextInputLayout>(R.id.rsTimeoutInput)
        val pingBeforeScanSwitch =
            dialogView.findViewById<android.widget.Switch>(R.id.pingBeforeScanSwitch)
        val saveToDbSwitch = dialogView.findViewById<android.widget.Switch>(R.id.saveToDbSwitch)

        maxThreadsInput.editText?.setText(settings.maxThreads.toString())
        timeoutInput.editText?.setText(settings.timeout.toString())
        rsTimeoutInput.editText?.setText((settings.rsTimeout / 1000).toString())
        pingBeforeScanSwitch.isChecked = settings.pingBeforeScan
        pingBeforeScanSwitch.isEnabled = settings.pingBeforeScan
        saveToDbSwitch.isChecked = settings.saveToLocalDb

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Router Scan Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val maxThreads = maxThreadsInput.editText?.text?.toString()?.toIntOrNull() ?: 10
                val timeout = timeoutInput.editText?.text?.toString()?.toIntOrNull() ?: 1000
                val rsTimeout =
                    (rsTimeoutInput.editText?.text?.toString()?.toIntOrNull() ?: 30) * 1000L
                val pingBefore = pingBeforeScanSwitch.isChecked
                val saveToDb = saveToDbSwitch.isChecked
                viewModel.updateSettings(
                    maxThreads.coerceIn(1, 200),
                    timeout.coerceIn(300, 3000).toLong(),
                    rsTimeout.coerceIn(1000, 120000),
                    pingBefore,
                    saveToDb
                )
                Toast.makeText(requireContext(), "Settings updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showScanCompleteDialog() {
        val state = viewModel.state.value ?: return
        val successCount = state.successCount
        val failureCount = state.failureCount
        val totalCount = state.totalToScan
        val activeIps = state.successfulPingCount
        val pingedCount = state.pingCount

        val successful = viewModel.getSuccessfulResults()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Scan Complete")
            .setMessage(
                "Scanned: $totalCount\n" +
                        "Successful: $successCount\n" +
                        "Failed: $failureCount\n" +
                        "Active IPs: $activeIps / $pingedCount"
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Save") { _, _ ->
                exportToCsv()
            }
            .apply {
                if (successful.isNotEmpty()) {
                    setNegativeButton(getString(R.string.router_scan_upload_3wifi)) { _, _ ->
                        uploadResultsTo3WiFi(successful)
                    }
                }
            }
            .show()
    }

    private fun exportToCsv() {
        val state = viewModel.state.value ?: return
        if (state.results.isEmpty()) {
            Toast.makeText(requireContext(), "No results to export", Toast.LENGTH_SHORT).show()
            return
        }

        val csv = buildString {
            appendLine("\"IP Address\";\"Port\";\"Time (ms)\";\"Status\";\"Server Type\";\"Authorization\";\"Sec\";\"Server name\";\"BSSID\";\"ESSID\";\"Key\";\"WPS PIN\";\"Latitude\";\"Longitude\"")
            state.results.filter { it.success }.forEach { result ->
                appendLine(
                    "\"${result.ip}\";\"${result.port}\";\"\";\"${result.status}\";\"${result.serverType}\";\"${result.auth}\";\"${result.sec}\";\"${result.title}\";\"${result.bssid}\";\"${result.ssid}\";\"${result.psk}\";\"${result.wps}\";\"${result.lat}\";\"${result.lon}\""
                )
            }
        }

        requireContext().openFileOutput(
            "router_scan_results.csv",
            android.content.Context.MODE_PRIVATE
        ).use {
            it.write(csv.toByteArray())
        }

        Toast.makeText(requireContext(), "Exported to router_scan_results.csv", Toast.LENGTH_SHORT)
            .show()
        viewModel.addConsoleLine("[+] Exported CSV to router_scan_results.csv")
    }

    private fun showFullOutputDialog(result: RouterScanResult) {
        val scrollView = android.widget.ScrollView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val textView = android.widget.TextView(requireContext()).apply {
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, android.R.color.primary_text_dark))
            setPadding(16, 16, 16, 16)
            text = result.fullOutput
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(
            textView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${result.ip}:${result.port} - Full Output")
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .setOnDismissListener {
                try {
                    val clipboard =
                        requireContext().getSystemService(android.content.ClipboardManager::class.java)
                    val clip =
                        android.content.ClipData.newPlainText("full_output", result.fullOutput)
                    clipboard?.setPrimaryClip(clip)
                    Toast.makeText(
                        requireContext(),
                        "Output copied to clipboard",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) {
                }
            }
            .show()
    }

    private fun toggleConsole() {
        consoleVisible = !consoleVisible
        binding.consoleContent.visibility = if (consoleVisible) View.VISIBLE else View.GONE
        binding.buttonToggleConsole.text = if (consoleVisible) "Console ▲" else "Console ▼"
    }

    private fun restoreState() {
        val saved = viewModel.restoreState()
        saved?.let { (ip, ports) ->
            binding.editTextIp.setText(ip)
            binding.editTextPorts.setText(ports)
        }
        val settings = viewModel.getScanSettings()
        binding.editTextMaxThreads.text = "Threads: ${settings.maxThreads}"
    }

    private fun uploadSingleTo3WiFi(result: RouterScanResult) {
        uploadResultsTo3WiFi(listOf(result))
    }

    private fun uploadAllTo3WiFi() {
        val successful = viewModel.getSuccessfulResults()
        if (successful.isEmpty()) {
            Toast.makeText(requireContext(), R.string.router_scan_upload_empty, Toast.LENGTH_SHORT)
                .show()
            return
        }
        uploadResultsTo3WiFi(successful)
    }

    private fun uploadResultsTo3WiFi(results: List<RouterScanResult>) {
        viewLifecycleOwner.lifecycleScope.launch {
            dbSetupViewModel.loadDbList()
            delay(300)
            val servers = dbSetupViewModel.dbList.value?.filter { it.dbType == DbType.WIFI_API }
                ?: emptyList()
            if (servers.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    R.string.router_scan_upload_no_servers,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val server = if (servers.size == 1) {
                servers[0]
            } else {
                val names = servers.map { "${it.type} - ${it.path}" }.toTypedArray()
                var selectedIndex = -1
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.router_scan_upload_select_server)
                    .setSingleChoiceItems(names, 0) { _, which -> selectedIndex = which }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (selectedIndex >= 0) {
                            doUpload(results, servers[selectedIndex])
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@launch
            }
            doUpload(results, server)
        }
    }

    private fun doUpload(results: List<RouterScanResult>, server: DbItem) {
        val commentInput =
            com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
                setHint(R.string.router_scan_upload_comment_hint)
                setPadding(48, 16, 48, 16)
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.router_scan_upload_3wifi)
            .setMessage(
                "${getString(R.string.router_scan_upload_uploading)} ${results.size} ${
                    getString(
                        R.string.router_scan_results
                    ).lowercase()
                } → ${server.path}"
            )
            .setView(commentInput)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val comment = commentInput.text?.toString() ?: ""
                viewModel.uploadTo3WiFi(results, server, comment)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "RouterScanFragment"
    }
}
