package com.lsd.wififrankenstein.ui.airodump

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentAirodumpBinding
import com.lsd.wififrankenstein.databinding.ItemInterfaceStatusBinding
import com.lsd.wififrankenstein.service.HandshakeCaptureService
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeStorageManager
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.ui.pixiedust.ConsoleAdapter
import com.lsd.wififrankenstein.ui.pixiedust.PixieDustAdapter
import com.lsd.wififrankenstein.util.CaptureFormat
import com.lsd.wififrankenstein.util.CaptureStats
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.ChrootType
import com.lsd.wififrankenstein.util.DetectionState
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AirodumpFragment : Fragment() {

    private var _binding: FragmentAirodumpBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AirodumpViewModel by activityViewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return AirodumpViewModel(requireActivity().application) as T
            }
        }
    }

    private lateinit var iwWifiManager: IwWifiManager
    private lateinit var chrootManager: ChrootManager

    private var pixieDustAdapter: PixieDustAdapter? = null
    private var consoleAdapter: ConsoleAdapter? = null
    private var statusAdapter: InterfaceStatusAdapter? = null
    private var currentClients: List<com.lsd.wififrankenstein.util.AirodumpClient> = emptyList()

    private var selectedNetwork: IwWifiNetwork? = null
    private var consoleVisible = true
    private var setupComplete = false
    private var scanInterface: String = "wlan0"
    private var captureInterface: String = "wlan0"
    private var deauthInterface: String = ""
    private var statusPollingJob: Job? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var currentSnackbar: Snackbar? = null
    private var backgroundAttached = false
    private var backgroundReceiver: BroadcastReceiver? = null
    private var backgroundStatsJob: Job? = null

    private var currentStepIndex = 0
    private val totalSteps = 4

    private var pendingPrefillBssid: String = ""
    private var pendingPrefillSsid: String = ""
    private var pendingPrefillChannel: String = ""
    private var pendingPrefillInterface: String = ""

    enum class Step(val index: Int, val titleRes: Int) {
        INTERFACE(0, R.string.airodump_step1_title),
        SCAN(1, R.string.airodump_step2_title),
        ATTACK(2, R.string.airodump_step3_title),
        RESULTS(3, R.string.airodump_step4_title)
    }

    companion object {
        private const val TAG = "AirodumpFrag"
        private const val MODE_SCAN = 0
        private const val MODE_MANUAL = 1
        private const val PREFS_NAME = "handshake_capture"
        private const val KEY_SCAN_IFACE = "scan_interface"
        private const val KEY_CAPTURE_IFACE = "capture_interface"
        private const val KEY_DEAUTH_IFACE = "deauth_interface"
        private const val STATUS_POLL_INTERVAL_MS = 3000L
        private const val DEAUTH_COUNT_DEFAULT = "5"
        private const val RECENT_CAPTURES_LIMIT = 3
        private const val STATE_STEP_INDEX = "step_index"
        private const val STATE_CONSOLE_VISIBLE = "console_visible"
        private const val STATE_SETUP_COMPLETE = "setup_complete"
    }

    private val saveConsoleLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingConsoleText
        pendingConsoleText = null
        if (uri == null || text == null) return@registerForActivityResult
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { os ->
                os.write(text.toByteArray())
            }
            Toast.makeText(requireContext(), R.string.airodump_console_saved, Toast.LENGTH_SHORT)
                .show()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.airodump_console_save_failed, e.message),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    private var pendingConsoleText: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAirodumpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        iwWifiManager = IwWifiManager(requireContext())
        chrootManager = ChrootManager.get(requireContext())

        currentStepIndex = savedInstanceState?.getInt(STATE_STEP_INDEX, 0) ?: 0

        if (savedInstanceState == null) {
            pendingPrefillBssid = arguments?.getString("bssid").orEmpty()
            pendingPrefillSsid = arguments?.getString("ssid").orEmpty()
            pendingPrefillChannel = arguments?.getString("channel").orEmpty()
            pendingPrefillInterface = arguments?.getString("interface").orEmpty()
        }

        checkChrootAndSetup()
        attachToBackgroundCaptureWhenReady()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP_INDEX, currentStepIndex)
        outState.putBoolean(STATE_CONSOLE_VISIBLE, consoleVisible)
        outState.putBoolean(STATE_SETUP_COMPLETE, setupComplete)
    }

    private fun goToStep(step: Step, forward: Boolean = step.index > currentStepIndex) {
        if (_binding == null) return
        currentStepIndex = step.index
        val ctx = context ?: return
        binding.viewFlipperSteps.inAnimation = AnimationUtils.loadAnimation(
            ctx, if (forward) R.anim.slide_in_right else R.anim.slide_in_left
        )
        binding.viewFlipperSteps.outAnimation = AnimationUtils.loadAnimation(
            ctx, if (forward) R.anim.slide_out_left else R.anim.slide_out_right
        )
        binding.viewFlipperSteps.displayedChild = step.index

        updateStepIndicators()
        onStepEntered(step)
    }

    private fun updateStepIndicators() {
        val title = getString(Step.values()[currentStepIndex].titleRes)
        val text =
            getString(R.string.airodump_step_indicator, currentStepIndex + 1, totalSteps, title)
        _binding?.textStepIndicator1?.text = text
        _binding?.textStepIndicator2?.text = text
        _binding?.textStepIndicator3?.text = text
        _binding?.textStepIndicator4?.text = text
    }

    private fun onStepEntered(step: Step) {
        when (step) {
            Step.INTERFACE -> {
            }

            Step.SCAN -> {
                updateInterfaceStatusCompact()
                pixieDustAdapter?.updateNetworks(viewModel.networks.value.orEmpty())
            }

            Step.ATTACK -> {
                attachToBackgroundCaptureWhenReady()
            }

            Step.RESULTS -> {
            }
        }
    }

    private fun checkChrootAndSetup() {
        val chrootType = chrootManager.getChrootType()
        if (chrootType is ChrootType.None || chrootType is ChrootType.Rootless) {
            setupComplete = false
            showChrootPlaceholder(withInstall = false)
        } else if (chrootType is ChrootType.RootMissing || chrootType is ChrootType.RootWithoutChroot) {
            setupComplete = false
            showChrootPlaceholder(withInstall = true)
        } else if (!setupComplete) {
            setupComplete = true
            showSteps()
            setupDeauthCountDropdown()
            setupCaptureFormat()
            setupInterfacesSection()
            setupRecentCaptures()
            setupTabLayout()
            setupButtons()
            setupScanSection()
            setupConsole()
            setupInterfaceStatusAdapter()
            setupConsoleCollapsible()
            observeViewModel()
            setupStatusClickListeners()
            viewModel.loadStorage()
            viewModel.pollInterfaceStatus()
            registerPrefsListener()
            startStatusPolling()

            binding.viewFlipperSteps.displayedChild = currentStepIndex
            updateStepIndicators()

            lifecycleScope.launch {
                delay(1000)
                if (!backgroundAttached && !HandshakeCaptureService.isActive()) {
                    viewModel.checkLeftoverCaptures()
                }
            }

            applyPrefillFromArguments()
        }
    }

    private fun applyPrefillFromArguments() {
        if (_binding == null || pendingPrefillBssid.isBlank()) return
        val bssid = pendingPrefillBssid.uppercase()
        binding.tabLayout.getTabAt(MODE_MANUAL)?.select()
        binding.editTextBssid.setText(bssid)
        if (pendingPrefillSsid.isNotBlank()) {
            binding.editTextSsid.setText(pendingPrefillSsid)
        }
        goToStep(Step.SCAN)
    }

    private fun attachToBackgroundCapture() {
        if (_binding == null || backgroundAttached) return
        val active = HandshakeCaptureService.getActive() ?: return

        backgroundAttached = true
        viewModel.resetCaptureStats()
        viewModel.addConsoleLine("[*] Attached to background capture")
        HandshakeCaptureService.getConsoleHistory().forEach { viewModel.addConsoleLine(it) }
        HandshakeCaptureService.getLatestStats()?.let { viewModel.updateCaptureStats(it) }

        binding.textAttackTargetSsid.text =
            active.ssid.ifEmpty { getString(R.string.pixiedust_hidden_network) }
        binding.textAttackTargetInfo.text = "${active.bssid}  ·  CH ${active.channel}"

        attachBackgroundReceiver()
        startBackgroundStatsPolling()
        goToStep(Step.ATTACK)
    }

    private fun attachToBackgroundCaptureWhenReady(waitForStart: Boolean = false) {
        if (_binding == null || backgroundAttached) return
        if (HandshakeCaptureService.getActive() != null) {
            attachToBackgroundCapture()
            return
        }
        if (!waitForStart) return
        viewLifecycleOwner.lifecycleScope.launch {
            var attempts = 0
            while (attempts < 50 && !backgroundAttached) {
                delay(200)
                attempts++
                if (HandshakeCaptureService.getActive() != null) {
                    attachToBackgroundCapture()
                    return@launch
                }
            }
        }
    }

    private fun attachBackgroundReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (viewLifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
                when (intent.action) {
                    HandshakeCaptureService.BROADCAST_CAPTURE_LINE -> {
                        intent.getStringExtra(HandshakeCaptureService.EXTRA_LINE)
                            ?.let { viewModel.addConsoleLine(it) }
                    }

                    HandshakeCaptureService.BROADCAST_CAPTURE_EVENT -> {
                        when (intent.getStringExtra(HandshakeCaptureService.EXTRA_EVENT)) {
                            "HANDSHAKE" ->
                                viewModel.addConsoleLine("[+] Handshake detected by airodump")

                            "PMKID" -> viewModel.addConsoleLine("[+] PMKID detected by airodump")
                        }
                    }

                    HandshakeCaptureService.BROADCAST_CAPTURE_COMPLETE -> {
                        val saved = intent.getStringExtra(HandshakeCaptureService.EXTRA_SAVED_PATH)
                        val valid =
                            intent.getBooleanExtra(HandshakeCaptureService.EXTRA_VALID, false)
                        if (valid && !saved.isNullOrEmpty()) {
                            viewModel.addConsoleLine("[+] Saved to storage: $saved")
                            Toast.makeText(
                                requireContext(),
                                R.string.background_capture_saved,
                                Toast.LENGTH_LONG
                            ).show()
                            detachBackgroundCapture()
                            navigateToStorage(saved)
                        } else {
                            viewModel.addConsoleLine("[-] Background capture finished — no valid handshake")
                        }
                    }

                    HandshakeCaptureService.BROADCAST_CAPTURE_ERROR -> {
                        val msg = intent.getStringExtra(HandshakeCaptureService.EXTRA_ERROR_MESSAGE)
                        viewModel.addConsoleLine("[!] Background capture error: $msg")
                    }
                }
            }
        }
        backgroundReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(HandshakeCaptureService.BROADCAST_CAPTURE_LINE)
            addAction(HandshakeCaptureService.BROADCAST_CAPTURE_EVENT)
            addAction(HandshakeCaptureService.BROADCAST_CAPTURE_COMPLETE)
            addAction(HandshakeCaptureService.BROADCAST_CAPTURE_ERROR)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(receiver, filter)
    }

    private fun detachBackgroundCapture() {
        backgroundAttached = false
        backgroundStatsJob?.cancel()
        backgroundStatsJob = null
        backgroundReceiver?.let {
            try {
                LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        backgroundReceiver = null
    }

    private fun startBackgroundStatsPolling() {
        backgroundStatsJob?.cancel()
        backgroundStatsJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && backgroundAttached) {
                HandshakeCaptureService.getLatestStats()?.let { viewModel.updateCaptureStats(it) }
                delay(2000)
            }
        }
    }

    private fun showSteps() {
        _binding?.placeholderChroot?.visibility = View.GONE
        _binding?.viewFlipperSteps?.visibility = View.VISIBLE
    }

    private fun showChrootPlaceholder(withInstall: Boolean) {
        binding.placeholderChroot.visibility = View.VISIBLE
        binding.viewFlipperSteps.visibility = View.GONE
        binding.topProgressBar.visibility = View.GONE

        binding.textViewChrootTitle.text = getString(R.string.menu_airodump)

        if (withInstall) {
            binding.textViewChrootMessage.text = getString(R.string.chroot_not_installed)
            binding.buttonInstallChroot.text = getString(R.string.install_chroot)
            binding.buttonInstallChroot.setOnClickListener { startChrootInstallation() }
        } else {
            binding.textViewChrootMessage.text = getString(R.string.airodump_root_required)
            binding.buttonInstallChroot.text = getString(R.string.go_back)
            binding.buttonInstallChroot.setOnClickListener { findNavController().navigateUp() }
        }
    }

    private fun startChrootInstallation() {
        binding.progressBarChrootInstall.visibility = View.VISIBLE
        binding.textViewChrootStatus.visibility = View.VISIBLE
        binding.buttonInstallChroot.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val success = try {
                chrootManager.downloadAndInstall(
                    onProgress = { progress ->
                        _binding?.textViewChrootStatus?.text =
                            "${getString(R.string.chroot_installing)} $progress%"
                    },
                    onStatusUpdate = { status ->
                        _binding?.textViewChrootStatus?.text = status
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "downloadAndInstall failed", e)
                false
            }

            if (_binding == null) return@launch

            _binding?.progressBarChrootInstall?.visibility = View.GONE
            _binding?.buttonInstallChroot?.isEnabled = true

            if (success) {
                _binding?.textViewChrootStatus?.text = getString(R.string.chroot_installed_success)
                checkChrootAndSetup()
            } else {

            }
        }
    }

    private fun setupDeauthCountDropdown() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedCount =
            prefs.getString("deauth_count", DEAUTH_COUNT_DEFAULT) ?: DEAUTH_COUNT_DEFAULT
        binding.editDeauthCount.setText(savedCount)
        binding.editDeauthCount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString() ?: return
                if (text.isNotEmpty()) {
                    prefs.edit().putString("deauth_count", text).apply()
                }
            }
        })

        val autoClients = prefs.getBoolean("auto_deauth_clients", true)
        val autoBroadcast = prefs.getBoolean("auto_deauth_broadcast", true)
        val excludeSelf = prefs.getBoolean("exclude_self", false)
        binding.switchAutoDeauthClients.isChecked = autoClients
        binding.switchAutoDeauthBroadcast.isChecked = autoBroadcast
        binding.switchExcludeSelf.isChecked = excludeSelf

        binding.switchAutoDeauthClients.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_deauth_clients", isChecked).apply()
            updateExcludeSelfWarning(
                isChecked,
                binding.switchAutoDeauthBroadcast.isChecked,
                binding.switchExcludeSelf.isChecked
            )
        }
        binding.switchAutoDeauthBroadcast.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_deauth_broadcast", isChecked).apply()
            updateExcludeSelfWarning(
                binding.switchAutoDeauthClients.isChecked,
                isChecked,
                binding.switchExcludeSelf.isChecked
            )
        }
        binding.switchExcludeSelf.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("exclude_self", isChecked).apply()
            updateExcludeSelfWarning(
                binding.switchAutoDeauthClients.isChecked,
                binding.switchAutoDeauthBroadcast.isChecked,
                isChecked
            )
        }

        updateExcludeSelfWarning(autoClients, autoBroadcast, excludeSelf)
    }

    private fun updateExcludeSelfWarning(
        autoClients: Boolean,
        autoBroadcast: Boolean,
        excludeSelf: Boolean
    ) {
        if (_binding == null) return
        val show = excludeSelf && autoBroadcast
        binding.textExcludeSelfWarning.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setupCaptureFormat() {
        binding.toggleCaptureFormat.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val format = when (checkedId) {
                R.id.btnFormatPcapng -> CaptureFormat.PCAPNG
                R.id.btnFormatPcap -> CaptureFormat.PCAP
                R.id.btnFormatCap -> CaptureFormat.CAP
                else -> CaptureFormat.DEFAULT
            }
            viewModel.setCaptureFormat(format)
            updateCaptureFormatDescription(format)
            if (format != CaptureFormat.PCAPNG) {
                currentSnackbar?.dismiss()
                currentSnackbar = Snackbar.make(
                    binding.root,
                    R.string.airodump_format_recommend,
                    Snackbar.LENGTH_LONG
                )
                currentSnackbar?.show()
            }
        }
        updateCaptureFormatDescription(CaptureFormat.DEFAULT)
    }

    private fun updateCaptureFormatDescription(format: CaptureFormat) {
        val ctx = context ?: return
        binding.textCaptureFormatDesc.text = when (format) {
            CaptureFormat.PCAPNG -> getString(R.string.airodump_format_pcapng_desc)
            CaptureFormat.PCAP -> getString(R.string.airodump_format_pcap_alt_desc)
            CaptureFormat.CAP -> getString(R.string.airodump_format_cap_desc)
        }
        binding.textCaptureFormatDesc.setTextColor(
            if (format == CaptureFormat.PCAPNG)
                ContextCompat.getColor(ctx, R.color.text_secondary)
            else
                ContextCompat.getColor(ctx, R.color.warning_orange)
        )
    }

    private fun setupInterfacesSection() {
        loadInterfaces()

        binding.buttonRefresh.setOnClickListener {
            viewModel.loadInterfaces()
            viewLifecycleOwner.lifecycleScope.launch {
                delay(500)
                loadInterfaces()
            }
        }

        binding.buttonContinueToScan.setOnClickListener {
            if (captureInterface.isBlank()) {
                Toast.makeText(requireContext(), "Select a capture interface", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            goToStep(Step.SCAN)
        }
    }

    private fun setupRecentCaptures() {

    }

    private fun setupTabLayout() {
        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(
            binding.tabLayout.newTab().setText(getString(R.string.scan_networks))
        )
        binding.tabLayout.addTab(
            binding.tabLayout.newTab().setText(getString(R.string.airodump_enter_bssid))
        )
        binding.tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    MODE_SCAN -> showScanMode()
                    MODE_MANUAL -> showManualMode()
                }
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun showScanMode() {
        binding.cardManualInput.visibility = View.GONE
        binding.recyclerViewNetworks.visibility = View.VISIBLE
        binding.textEmptyState.visibility =
            if (pixieDustAdapter?.itemCount == 0) View.VISIBLE else View.GONE
        binding.buttonScan.isEnabled = true
        binding.buttonScan.visibility = View.VISIBLE
    }

    private fun showManualMode() {
        binding.cardManualInput.visibility = View.VISIBLE
        binding.recyclerViewNetworks.visibility = View.GONE
        binding.textEmptyState.visibility = View.GONE
        binding.buttonScan.visibility = View.GONE
    }

    private fun setupScanSection() {
        pixieDustAdapter = PixieDustAdapter(
            onNetworkClick = { network -> selectNetwork(network) }
        )
        binding.recyclerViewNetworks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = pixieDustAdapter
        }

        binding.buttonScan.setOnClickListener {
            scanNetworks()
        }
    }

    private fun getScanInterface(): String {
        return binding.autoCompleteScanInterface.text?.toString()?.takeIf { it.isNotEmpty() }
            ?: scanInterface
    }

    private fun setupConsole() {
        consoleAdapter = ConsoleAdapter(autoScroll = true)
        binding.recyclerViewConsole.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = consoleAdapter
        }
        consoleAdapter?.attachToRecyclerView(binding.recyclerViewConsole)
    }

    private fun setupInterfaceStatusAdapter() {
        statusAdapter = InterfaceStatusAdapter(showToggle = true) { ifaceName ->
            toggleInterfaceModeFor(ifaceName)
        }
        binding.recyclerViewInterfaceStatus.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = statusAdapter
        }
    }

    private fun setupConsoleCollapsible() {
        binding.layoutConsoleHeader.setOnClickListener { toggleConsole() }
        binding.iconToggleConsole.setOnClickListener { toggleConsole() }
        binding.iconCopyConsole.setOnClickListener { copyConsole() }
        binding.iconSaveConsole.setOnClickListener { saveConsole() }
    }

    private fun setupButtons() {
        binding.buttonBackToStep1.setOnClickListener { goToStep(Step.INTERFACE, forward = false) }
        binding.buttonBackToStep2.setOnClickListener {
            if (HandshakeCaptureService.isActive()) {
                Toast.makeText(
                    requireContext(),
                    R.string.airodump_background_started,
                    Toast.LENGTH_SHORT
                ).show()
                detachBackgroundCapture()
            }
            goToStep(Step.SCAN, forward = false)
        }
        binding.buttonBackToScan.setOnClickListener { goToStep(Step.INTERFACE, forward = false) }
        binding.buttonStopCapture.setOnClickListener { stopCapture() }
        binding.buttonManualDeauth.setOnClickListener { showManualDeauthSheet() }
        binding.buttonStatClients.setOnClickListener { showClientsSheet() }
        binding.buttonVerify.setOnClickListener { verifyHandshake() }
        binding.buttonExportHashcat.setOnClickListener { exportHashcat() }
        binding.buttonOpenFile.setOnClickListener { openFile() }
        binding.buttonCrackInStorage.setOnClickListener { navigateToStorageForCrack() }

        binding.buttonGoToStorage.setOnClickListener { navigateToStorage(null) }

        binding.buttonStartCaptureManual.setOnClickListener { startCapture() }

        binding.editTextBssid.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString()?.trim() ?: ""
                val valid = normalizeBssid(text) != null
                binding.buttonStartCaptureManual.isEnabled = valid
            }
        })

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keepHostWifi = prefs.getBoolean("keep_host_wifi", true)
        binding.switchKeepHostWifi.isChecked = keepHostWifi
        binding.switchKeepHostWifi.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("keep_host_wifi", isChecked).apply()
        }

    }

    private fun toggleInterfaceModeFor(ifaceName: String) {
        val currentMode = viewModel.interfaceStatuses.value
            ?.firstOrNull { it.name == ifaceName }?.mode
            ?: IwWifiManager.MODE_UNKNOWN

        val targetMode = if (currentMode == IwWifiManager.MODE_MONITOR) {
            IwWifiManager.MODE_MANAGED
        } else {
            IwWifiManager.MODE_MONITOR
        }

        viewModel.setInterfaceMode(ifaceName, targetMode, null)
    }

    private fun navigateToStorage(highlightFilePath: String?) {
        if (highlightFilePath.isNullOrEmpty()) {
            findNavController().navigate(R.id.nav_handshake_storage)
        } else {
            val args = Bundle().apply { putString("filePath", highlightFilePath) }
            findNavController().navigate(R.id.nav_handshake_storage, args)
        }
    }

    private fun registerPrefsListener() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                if (_binding == null) return@OnSharedPreferenceChangeListener

                when (key) {
                    KEY_SCAN_IFACE -> {
                        val newIface = sharedPreferences.getString(key, "wlan0") ?: "wlan0"
                        if (newIface != scanInterface) {
                            scanInterface = newIface
                            binding.autoCompleteScanInterface.setText(newIface, false)
                            viewModel.checkScanMode(newIface)
                        }
                    }

                    KEY_CAPTURE_IFACE -> {
                        val newIface = sharedPreferences.getString(key, "wlan0") ?: "wlan0"
                        if (newIface != captureInterface) {
                            captureInterface = newIface
                            binding.autoCompleteCaptureInterface.setText(newIface, false)
                            viewModel.checkInterfaceMode(newIface)
                        }
                    }

                    KEY_DEAUTH_IFACE -> {
                        val newIface = sharedPreferences.getString(key, "") ?: ""
                        if (newIface != deauthInterface) {
                            deauthInterface = newIface
                            val displayIface =
                                newIface.takeIf { it.isNotEmpty() } ?: captureInterface
                            binding.autoCompleteDeauthInterface.setText(displayIface, false)
                            val checkIface = newIface.takeIf { it.isNotEmpty() } ?: captureInterface
                            viewModel.checkDeauthMode(checkIface)
                        }
                    }

                    "auto_deauth_clients" -> {
                        binding.switchAutoDeauthClients.isChecked =
                            sharedPreferences.getBoolean(key, true)
                    }

                    "auto_deauth_broadcast" -> {
                        binding.switchAutoDeauthBroadcast.isChecked =
                            sharedPreferences.getBoolean(key, true)
                    }

                    "exclude_self" -> {
                        binding.switchExcludeSelf.isChecked =
                            sharedPreferences.getBoolean(key, false)
                    }

                    "deauth_count" -> {
                        val count = sharedPreferences.getString(key, DEAUTH_COUNT_DEFAULT)
                            ?: DEAUTH_COUNT_DEFAULT
                        binding.editDeauthCount.setText(count)
                    }
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun refreshInterfaceDropdowns() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ifaces = iwWifiManager.getAvailableInterfaces()
                val names = ifaces.map { it.name }.toTypedArray()
                val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                val savedScan = prefs.getString(KEY_SCAN_IFACE, scanInterface) ?: scanInterface
                val savedCapture =
                    prefs.getString(KEY_CAPTURE_IFACE, captureInterface) ?: captureInterface
                val savedDeauth =
                    prefs.getString(KEY_DEAUTH_IFACE, deauthInterface) ?: deauthInterface

                val validScan = validateInterface(savedScan, names, scanInterface)
                val validCapture = validateInterface(savedCapture, names, captureInterface)
                val validDeauth = if (savedDeauth.isEmpty()) deauthInterface else validateInterface(
                    savedDeauth,
                    names,
                    deauthInterface
                )

                setupDropdown(binding.autoCompleteScanInterface, names, validScan)
                setupDropdown(binding.autoCompleteCaptureInterface, names, validCapture)
                setupDropdown(binding.autoCompleteDeauthInterface, names, validDeauth)

                captureInterface =
                    binding.autoCompleteCaptureInterface.text?.toString() ?: captureInterface
                scanInterface = binding.autoCompleteScanInterface.text?.toString() ?: scanInterface
                deauthInterface =
                    binding.autoCompleteDeauthInterface.text?.toString() ?: deauthInterface
            } catch (_: Exception) {

            }
        }
    }

    private fun loadInterfaces() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ifaces = iwWifiManager.getAvailableInterfaces()
                val names = ifaces.map { it.name }.toTypedArray()
                val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                val savedScan = prefs.getString(KEY_SCAN_IFACE, names.getOrNull(0) ?: "wlan0")!!
                val savedCapture =
                    prefs.getString(KEY_CAPTURE_IFACE, names.getOrNull(0) ?: "wlan0")!!
                val savedDeauth = prefs.getString(KEY_DEAUTH_IFACE, "") ?: ""

                val argIface = pendingPrefillInterface
                pendingPrefillInterface = ""
                val preferredScan = if (argIface.isNotBlank()) argIface else savedScan

                val validScan = validateInterface(preferredScan, names, "wlan0")
                val validCapture = validateInterface(savedCapture, names, "wlan0")
                val validDeauth =
                    if (savedDeauth.isEmpty()) "" else validateInterface(savedDeauth, names, "")

                setupDropdown(binding.autoCompleteScanInterface, names, validScan)
                setupDropdown(binding.autoCompleteCaptureInterface, names, validCapture)

                setupDropdown(binding.autoCompleteDeauthInterface, names, validDeauth)

                scanInterface = binding.autoCompleteScanInterface.text?.toString() ?: "wlan0"
                captureInterface = binding.autoCompleteCaptureInterface.text?.toString() ?: "wlan0"
                deauthInterface = binding.autoCompleteDeauthInterface.text?.toString() ?: ""

                viewModel.checkInterfaceMode(captureInterface)
                viewModel.checkScanMode(scanInterface)
                val deauthCheckIface =
                    deauthInterface.takeIf { it.isNotEmpty() } ?: captureInterface
                viewModel.checkDeauthMode(deauthCheckIface)

                attachDropdownListeners(prefs)
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Log.e(TAG, "Failed to load interfaces", e)
                val fallbackNames = arrayOf("wlan0")
                setupDropdown(binding.autoCompleteScanInterface, fallbackNames, "wlan0")
                setupDropdown(binding.autoCompleteCaptureInterface, fallbackNames, "wlan0")
                setupDropdown(
                    binding.autoCompleteDeauthInterface,
                    fallbackNames,
                    fallbackNames.firstOrNull() ?: ""
                )
                scanInterface = "wlan0"
                captureInterface = "wlan0"
                deauthInterface = ""
                viewModel.checkInterfaceMode("wlan0")
                viewModel.checkScanMode("wlan0")
                viewModel.checkDeauthMode("wlan0")
            }
        }
    }

    private fun validateInterface(
        savedIface: String,
        availableNames: Array<String>,
        default: String
    ): String {
        return if (availableNames.contains(savedIface)) savedIface else default
    }

    private fun setupDropdown(
        autoComplete: AutoCompleteTextView,
        names: Array<String>,
        savedName: String
    ) {
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, names)
        autoComplete.setAdapter(adapter)
        val idx = names.indexOfFirst { it == savedName }
        autoComplete.setText(
            names.getOrNull(idx.coerceAtLeast(0)) ?: names.firstOrNull() ?: "",
            false
        )
    }

    private fun attachDropdownListeners(prefs: SharedPreferences) {
        binding.autoCompleteScanInterface.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val iface =
                    (parent?.getItemAtPosition(position) as? String) ?: return@OnItemClickListener
                prefs.edit().putString(KEY_SCAN_IFACE, iface).apply()
                scanInterface = iface
                viewModel.checkScanMode(iface)
            }

        binding.autoCompleteCaptureInterface.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val iface =
                    (parent?.getItemAtPosition(position) as? String) ?: return@OnItemClickListener
                prefs.edit().putString(KEY_CAPTURE_IFACE, iface).apply()
                captureInterface = iface
                viewModel.checkInterfaceMode(iface)
            }

        binding.autoCompleteDeauthInterface.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val iface = (parent?.getItemAtPosition(position) as? String) ?: ""
                prefs.edit().putString(KEY_DEAUTH_IFACE, iface).apply()
                deauthInterface = iface
                val checkIface = iface.takeIf { it.isNotEmpty() } ?: captureInterface
                viewModel.checkDeauthMode(checkIface)
            }
    }

    private fun scanNetworks() {
        val iface = binding.autoCompleteScanInterface.text?.toString()?.takeIf { it.isNotEmpty() }
            ?: scanInterface
        binding.textEmptyState.visibility = View.GONE
        binding.buttonScan.isEnabled = false

        selectedNetwork = null

        viewModel.scanNetworks(iface)
    }

    private fun selectNetwork(network: IwWifiNetwork) {
        selectedNetwork = network
        viewModel.selectNetwork(network)
        showTargetConfirmDialog(network)
    }

    private fun showTargetConfirmDialog(network: IwWifiNetwork) {
        val ssid = network.ssid.ifEmpty { getString(R.string.pixiedust_hidden_network) }
        val isWpa3 = network.securityType.contains("WPA3", ignoreCase = true) ||
                network.authSuite.uppercase() == "SAE" || network.authSuite.uppercase() == "OWE"
        val message = buildString {
            appendLine("SSID: $ssid")
            appendLine("BSSID: ${network.bssid}")
            appendLine("Channel: ${network.channel}")
            if (network.securityType.isNotEmpty()) {
                appendLine("Security: ${network.securityType}")
            }
            appendLine()
            if (isWpa3) {
                appendLine("⚠ WARNING: WPA3/SAE network detected!")
                appendLine("Traditional EAPOL handshake capture is NOT possible for WPA3.")
                appendLine("The capture will produce no usable hash.")
                appendLine()
            }
            append(getString(R.string.airodump_target_confirm_message))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.airodump_target_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.airodump_start_capture) { _, _ ->
                startCapture()
            }
            .show()
    }

    private fun startCapture() {
        if (HandshakeCaptureService.isActive()) {
            Toast.makeText(
                requireContext(),
                R.string.background_capture_already_running,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val captureIface =
            binding.autoCompleteCaptureInterface.text?.toString()?.takeIf { it.isNotEmpty() }
                ?: run {
                    Toast.makeText(
                        requireContext(),
                        "No capture interface selected",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
        val deauthIface = binding.autoCompleteDeauthInterface.text?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?: captureIface
        val bssid = if (binding.tabLayout.selectedTabPosition == MODE_MANUAL) {
            val input = binding.editTextBssid.text?.toString()?.trim() ?: ""
            if (input.isEmpty()) {
                Toast.makeText(requireContext(), "Enter BSSID", Toast.LENGTH_SHORT).show()
                return
            }
            normalizeBssid(input) ?: run {
                Toast.makeText(requireContext(), "Invalid BSSID format", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            selectedNetwork?.bssid?.uppercase() ?: run {
                Toast.makeText(requireContext(), "Select a network first", Toast.LENGTH_SHORT)
                    .show()
                return
            }
        }

        val channel = selectedNetwork?.channel?.takeIf { it.isNotEmpty() }
            ?: pendingPrefillChannel.takeIf { it.isNotEmpty() }
            ?: "1"
        val capturePrefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoDeauthClients = capturePrefs.getBoolean("auto_deauth_clients", true)
        val autoDeauthBroadcast = capturePrefs.getBoolean("auto_deauth_broadcast", true)
        val deauthCount = capturePrefs.getString("deauth_count", null)?.toIntOrNull()
            ?: DEAUTH_COUNT_DEFAULT.toInt()
        val keepHostWifi = capturePrefs.getBoolean("keep_host_wifi", true)
        val excludeSelf = capturePrefs.getBoolean("exclude_self", false)
        val deviceMac = if (excludeSelf) getDeviceMacAddress() else null

        viewModel.resetCaptureStats()
        val captureFormat = when (binding.toggleCaptureFormat.checkedButtonId) {
            R.id.btnFormatPcapng -> CaptureFormat.PCAPNG
            R.id.btnFormatPcap -> CaptureFormat.PCAP
            R.id.btnFormatCap -> CaptureFormat.CAP
            else -> CaptureFormat.DEFAULT
        }
        val ssid = selectedNetwork?.ssid?.takeIf { it.isNotEmpty() }
            ?: binding.editTextSsid.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: ""
        HandshakeCaptureService.start(
            requireContext(),
            iface = captureIface,
            deauthIface = deauthIface,
            bssid = bssid,
            channel = channel,
            essid = ssid,
            format = captureFormat,
            autoDeauthClients = autoDeauthClients,
            autoDeauthBroadcast = autoDeauthBroadcast,
            deauthCount = deauthCount,
            keepHostWifi = keepHostWifi,
            excludeSelf = excludeSelf,
            deviceMac = deviceMac
        )

        binding.textAttackTargetSsid.text =
            ssid.ifEmpty { getString(R.string.pixiedust_hidden_network) }
        binding.textAttackTargetInfo.text = "$bssid  ·  CH $channel"

        renderInterfaceChips()
        goToStep(Step.ATTACK)
        attachToBackgroundCaptureWhenReady(waitForStart = true)

        checkSelfConnectedWarning(bssid, autoDeauthBroadcast, excludeSelf)
    }

    private fun stopCapture() {
        HandshakeCaptureService.stop(requireContext())
        detachBackgroundCapture()
        viewModel.resetCaptureStats()
        goToStep(Step.SCAN, forward = false)
    }

    private fun showManualDeauthSheet() {
        val bssid = selectedNetwork?.bssid?.uppercase()
            ?: viewModel.captureStats.value?.targetBssid?.uppercase()
        if (bssid.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No target selected", Toast.LENGTH_SHORT).show()
            return
        }
        val sheet = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.sheet_manual_deauth, null)
        sheet.setContentView(view)
        sheet.show()

        view.findViewById<View>(R.id.buttonDeauthBroadcast).setOnClickListener {
            sheet.dismiss()
            performDeauth(bssid, null)
        }
        view.findViewById<View>(R.id.buttonDeauthAllClients).setOnClickListener {
            sheet.dismiss()
            if (currentClients.isEmpty()) {
                Toast.makeText(requireContext(), "No clients found yet", Toast.LENGTH_SHORT).show()
            } else {
                performDeauth(bssid, "*ALL*")
            }
        }
        view.findViewById<View>(R.id.buttonDeauthCancel).setOnClickListener {
            sheet.dismiss()
        }
    }

    private fun performDeauth(bssid: String, clientMac: String?) {
        val deviceMac = getDeviceMacAddress()
        if (clientMac != null && clientMac != "*ALL*" && deviceMac != null &&
            clientMac.equals(deviceMac, ignoreCase = true)
        ) {
            showSelfDeauthWarningDialog(bssid, clientMac)
            return
        }
        val iface = binding.autoCompleteDeauthInterface.text?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?: binding.autoCompleteCaptureInterface.text?.toString()
            ?: captureInterface
        val count = binding.editDeauthCount.text?.toString()?.toIntOrNull() ?: 5
        val channel = selectedNetwork?.channel
        viewModel.sendDeauth(iface, bssid, clientMac, count, channel)
    }

    private fun showSelfDeauthWarningDialog(bssid: String, clientMac: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.airodump_self_deauth_warning_title)
            .setMessage(R.string.airodump_self_deauth_warning_msg)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.airodump_proceed) { _, _ ->
                val iface = binding.autoCompleteDeauthInterface.text?.toString()
                    ?.takeIf { it.isNotEmpty() }
                    ?: binding.autoCompleteCaptureInterface.text?.toString()
                    ?: captureInterface
                val count = binding.editDeauthCount.text?.toString()?.toIntOrNull() ?: 5
                val channel = selectedNetwork?.channel
                viewModel.sendDeauth(iface, bssid, clientMac, count, channel)
            }
            .show()
    }

    private fun getDeviceMacAddress(): String? {
        return try {
            val network = java.net.NetworkInterface.getByName("wlan0") ?: return null
            val hardware = network.hardwareAddress ?: return null
            hardware.joinToString(":") { String.format("%02X", it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun checkSelfConnectedWarning(
        targetBssid: String,
        broadcastOn: Boolean,
        excludeSelf: Boolean
    ) {
        val isConnected = try {
            val wifiManager =
                requireContext().getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val bssid = wifiManager.connectionInfo?.bssid
            bssid != null && bssid.equals(targetBssid, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
        if (isConnected && (!excludeSelf || broadcastOn)) {
            binding.textAttackSelfConnectedWarning.visibility = View.VISIBLE
        } else {
            binding.textAttackSelfConnectedWarning.visibility = View.GONE
        }
    }

    private fun showClientsSheet() {
        val sheet = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.sheet_clients_list, null)
        sheet.setContentView(view)

        val recycler = view.findViewById<RecyclerView>(R.id.recyclerViewClients)
        val emptyText = view.findViewById<TextView>(R.id.textClientsEmpty)
        if (currentClients.isEmpty()) {
            recycler.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            recycler.layoutManager = LinearLayoutManager(requireContext())
            recycler.adapter = ClientsAdapter(currentClients)
        }
        sheet.show()
    }

    private fun verifyHandshake() {
        val result = viewModel.captureResult.value
        val capFile = result?.capFilePath ?: return
        viewModel.verifyHandshake(capFile)
    }

    private fun renderHcxpcapngtoolResult(result: AirodumpViewModel.HcxpcapngtoolResult) {
        binding.cardVerifyResult.visibility = View.VISIBLE
        binding.textHcxSummary.text = if (result.valid) {
            getString(R.string.airodump_hcx_handshake_valid)
        } else {
            getString(R.string.airodump_hcx_no_handshake)
        }
        binding.iconHcxResult.setImageResource(
            if (result.valid) R.drawable.ic_check else R.drawable.ic_close
        )
        binding.iconHcxResult.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (result.valid) R.color.success_green else R.color.error_red
            )
        )

        val details = buildString {
            append("Packets: ${result.packetsTotal}")
            append("\nEAPOL frames: ${result.eapolCount}")
            append("\nPMKID: ${result.pmkidCount}")
            if (result.essid.isNotEmpty()) append("\nESSID: ${result.essid}")
            if (result.channel > 0) append("\nChannel: ${result.channel}")
            if (result.durationSec > 0) append("\nDuration: ${result.durationSec}s")
        }
        binding.textHcxDetails.text = details

        binding.buttonShowFullHcxOutput.setOnClickListener {
            showHcxDialog(result.rawOutput, result.valid)
        }
    }

    private fun exportHashcat() {
        val result = viewModel.captureResult.value
        val capFile = result?.capFilePath ?: return
        val formats = arrayOf(
            getString(R.string.airodump_export_hccapx),
            getString(R.string.airodump_export_22000),
            getString(R.string.airodump_export_pmkid)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.airodump_export_format_title)
            .setItems(formats) { _, which ->
                when (which) {
                    0 -> viewModel.exportToHashcat(capFile)
                    1 -> viewModel.exportTo22000(capFile)
                    2 -> viewModel.exportPmkidOnly(capFile)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openFile() {
        val externalStorage = android.os.Environment.getExternalStorageDirectory()
        val dir = File(
            HandshakeStorageManager.STORAGE_DIR.replaceFirst(
                "/sdcard",
                externalStorage.absolutePath
            )
        )
        if (!dir.exists() || !dir.isDirectory) {
            Toast.makeText(requireContext(), "Storage folder not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                dir
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.airodump_open_file)))
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        android.provider.DocumentsContract.buildDocumentUri(
                            "${requireContext().packageName}.fileprovider",
                            "handshakes"
                        ),
                        "vnd.android.document/directory"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.airodump_open_file)))
            } catch (e2: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Cannot open folder: ${e2.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun navigateToStorageForCrack() {
        val result = viewModel.captureResult.value
        navigateToStorage(result?.capFilePath)
    }

    private fun toggleConsole() {
        consoleVisible = !consoleVisible
        if (consoleVisible) {
            binding.recyclerViewConsole.visibility = View.VISIBLE
            binding.iconToggleConsole.setImageResource(R.drawable.ic_expand_less)
        } else {
            binding.recyclerViewConsole.visibility = View.GONE
            binding.iconToggleConsole.setImageResource(R.drawable.ic_expand_more)
        }
    }

    private fun copyConsole() {
        val lines = consoleAdapter?.getLines() ?: emptyList()
        if (lines.isNotEmpty()) {
            val text = lines.joinToString("\n")
            val clip = ClipData.newPlainText("Airodump Console", text)
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE).let {
                (it as ClipboardManager).setPrimaryClip(clip)
            }
            Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun saveConsole() {
        val lines = consoleAdapter?.getLines() ?: emptyList()
        if (lines.isNotEmpty()) {
            pendingConsoleText = lines.joinToString("\n")
            saveConsoleLauncher.launch("airodump_console.txt")
        }
    }

    private fun normalizeBssid(input: String): String? {
        val clean = input.replace("[^0-9A-Fa-f]".toRegex(), "")
        if (clean.length != 12) return null
        return clean.chunked(2).joinToString(":").uppercase()
    }

    private fun renderInterfaceChips() {
        binding.chipGroupInterfaces.removeAllViews()
        val statuses = viewModel.interfaceStatuses.value.orEmpty()
        val relevant = statuses.filter { it.name == captureInterface || it.name == deauthInterface }
        if (relevant.isEmpty()) {
            val chip = Chip(requireContext()).apply {
                text = "$captureInterface —"
                isClickable = false
            }
            binding.chipGroupInterfaces.addView(chip)
            return
        }
        for (s in relevant) {
            val label = "${s.name} · ${modeLabel(s.mode)}"
            val chip = Chip(requireContext()).apply {
                text = label
                isClickable = false
                chipBackgroundColor =
                    android.content.res.ColorStateList.valueOf(modeBgColor(s.mode))
                setTextColor(ContextCompat.getColor(context, modeTextColor(s.mode)))
            }
            binding.chipGroupInterfaces.addView(chip)
        }
    }

    private fun updateInterfaceStatusCompact() {

    }

    private fun modeLabel(mode: String): String = when (mode) {
        IwWifiManager.MODE_MANAGED -> "MANAGED"
        IwWifiManager.MODE_MONITOR -> "MONITOR"
        IwWifiManager.MODE_UNAVAILABLE -> "UNAVAILABLE"
        else -> "—"
    }

    private fun modeBgColor(mode: String): Int {
        val ctx = context ?: return android.R.color.darker_gray
        return ContextCompat.getColor(
            ctx, when (mode) {
                IwWifiManager.MODE_MANAGED -> R.color.green_500
                IwWifiManager.MODE_MONITOR -> R.color.blue_500
                IwWifiManager.MODE_UNAVAILABLE -> R.color.error_red
                else -> android.R.color.darker_gray
            }
        )
    }

    private fun modeTextColor(mode: String): Int = android.R.color.white

    private fun renderCaptureStats(stats: CaptureStats) {
        binding.textStatPwr.text = stats.power
        binding.textStatBeacons.text = stats.beacons
        binding.textStatData.text = stats.dataFrames
        binding.textStatRxq.text = stats.rxq
        binding.textStatEnc.text = stats.enc
        binding.textStatCipher.text = stats.cipher
        binding.textStatAuth.text = stats.auth
        binding.buttonStatClients.text = stats.clientCount.toString()

        binding.textStatBestClient.text = if (stats.bestClient.isNotEmpty()) {
            "${stats.bestClient}  (PWR ${stats.bestClientPwr}, Rate ${stats.bestClientRate})"
        } else {
            "—"
        }

        val ctx = context ?: return
        renderStatusIndicator(
            container = binding.statusPmkidContainer,
            icon = binding.iconStatusPmkid,
            text = binding.textStatusPmkid,
            state = stats.pmkidState,
            confirmedText = getString(R.string.airodump_pmkid_found),
            airodumpText = getString(R.string.airodump_pmkid_airodump),
            missingText = getString(R.string.airodump_pmkid_missing)
        )
        renderStatusIndicator(
            container = binding.statusHandshakeContainer,
            icon = binding.iconStatusHandshake,
            text = binding.textStatusHandshake,
            state = stats.handshakeState,
            confirmedText = getString(R.string.airodump_hs_found),
            airodumpText = getString(R.string.airodump_hs_airodump),
            missingText = getString(R.string.airodump_hs_missing)
        )

        currentClients = stats.clients
    }

    private fun renderStatusIndicator(
        container: View,
        icon: ImageView,
        text: TextView,
        state: DetectionState,
        confirmedText: String,
        airodumpText: String,
        missingText: String
    ) {
        val ctx = context ?: return
        when (state) {
            DetectionState.CONFIRMED -> {
                container.setBackgroundResource(R.drawable.bg_status_found)
                text.text = confirmedText
                text.setTextColor(ContextCompat.getColor(ctx, R.color.success_green))
                icon.setImageResource(R.drawable.ic_check)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.success_green)
                )
            }

            DetectionState.AIRODUMP -> {
                container.setBackgroundResource(R.drawable.bg_status_detected)
                text.text = airodumpText
                text.setTextColor(ContextCompat.getColor(ctx, R.color.warning_orange))
                icon.setImageResource(R.drawable.ic_check)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.warning_orange)
                )
            }

            DetectionState.NONE -> {
                container.setBackgroundResource(R.drawable.bg_status_pending)
                text.text = missingText
                text.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                icon.setImageResource(R.drawable.ic_close)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.text_secondary)
                )
            }
        }
    }

    private fun setupStatusClickListeners() {
        binding.statusHandshakeContainer.setOnClickListener {
            lifecycleScope.launch {
                val stats = viewModel.captureStats.value
                if (stats != null && stats.handshakeState != DetectionState.NONE) {
                    val capFile = viewModel.getCurrentCapFilePath()
                    if (capFile != null) {
                        showHcxpcapngtoolResult(capFile, isPmkid = false)
                    } else {
                        viewModel.addConsoleLine("[-] No cap file path available for verification")
                    }
                }
            }
        }
        binding.statusPmkidContainer.setOnClickListener {
            lifecycleScope.launch {
                val stats = viewModel.captureStats.value
                if (stats != null && stats.pmkidState != DetectionState.NONE) {
                    val capFile = viewModel.getCurrentCapFilePath()
                    if (capFile != null) {
                        showHcxpcapngtoolResult(capFile, isPmkid = true)
                    } else {
                        viewModel.addConsoleLine("[-] No cap file path available for verification")
                    }
                }
            }
        }
    }

    private fun showHcxpcapngtoolResult(capFile: String, isPmkid: Boolean) {
        viewModel.addConsoleLine("[*] Running hcxpcapngtool verification...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val output = viewModel.runHcxpcapngtool(capFile, isPmkid)
                val isValid = if (isPmkid) {
                    output.contains("WPA01", ignoreCase = true) ||
                            output.contains("PMKID", ignoreCase = true)
                } else {
                    output.contains("WPA01", ignoreCase = true) ||
                            output.contains("WPA02", ignoreCase = true) ||
                            output.contains("EAPOL", ignoreCase = true)
                }
                withContext(Dispatchers.Main) {
                    showHcxDialog(output, isValid)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    viewModel.addConsoleLine("[-] hcxpcapngtool error: ${e.message}")
                }
            }
        }
    }

    private fun showHcxDialog(output: String, isValid: Boolean) {
        val ctx = context ?: return
        val title = getString(R.string.airodump_hcx_result_title)
        val statusText = if (isValid) {
            getString(R.string.airodump_hcx_handshake_valid)
        } else {
            getString(R.string.airodump_hcx_no_handshake)
        }
        val displayText = "$statusText\n\n--- hcxpcapngtool output ---\n$output"
            .lines()
            .take(80)
            .joinToString("\n")

        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(displayText)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(getString(R.string.airodump_share)) { _, _ ->
                shareText(output)
            }
            .show()
    }

    private fun shareText(text: String) {
        val ctx = context ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(intent, null))
    }

    private fun observeViewModel() {
        viewModel.consoleLines.observe(viewLifecycleOwner) { lines ->
            consoleAdapter?.setLines(lines)
        }

        viewModel.captureStats.observe(viewLifecycleOwner) { stats ->
            renderCaptureStats(stats)
        }

        viewModel.captureEvents.observe(viewLifecycleOwner) { events ->
            for (event in events) {
                when (event) {
                    "PMKID" -> viewModel.addConsoleLine("[+] PMKID detected by airodump")
                    "HANDSHAKE" -> viewModel.addConsoleLine("[+] Handshake detected by airodump")
                }
            }
            if (events.isNotEmpty()) {
                viewModel.clearCaptureEvents()
            }
        }

        viewModel.savePromptRequest.observe(viewLifecycleOwner) { req ->
            if (req == null) {
                currentSnackbar?.dismiss()
                currentSnackbar = null
            } else {
                showSavePrompt(req)
            }
        }

        viewModel.saveUnverifiedRequest.observe(viewLifecycleOwner) { req ->
            if (req != null) {
                showUnverifiedSaveDialog(req)
            }
        }

        viewModel.captureResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                if (currentStepIndex != Step.RESULTS.index) {
                    goToStep(Step.RESULTS, forward = true)
                }
                renderResults(result)
                viewModel.clearCaptureResult()
            }
        }

        viewModel.verifyResult.observe(viewLifecycleOwner) { valid ->
            if (valid != null) {
                viewModel.clearVerifyResult()
            }
        }

        viewModel.hcxpcapngtoolResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                renderHcxpcapngtoolResult(result)
                viewModel.clearHcxpcapngtoolResult()
            }
        }

        viewModel.crackResult.observe(viewLifecycleOwner) { password ->
            if (password != null) {
                val clip = ClipData.newPlainText("WiFi Password", password)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE).let {
                    (it as ClipboardManager).setPrimaryClip(clip)
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.airodump_key_found, password),
                    Toast.LENGTH_LONG
                ).show()
                viewModel.clearCrackResult()
            }
        }

        viewModel.statusText.observe(viewLifecycleOwner) { status ->
            binding.textStatus.text = status
        }

        viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
            binding.progressSmall.visibility = if (scanning) View.VISIBLE else View.GONE
            binding.buttonScan.isEnabled = !scanning
        }

        viewModel.networks.observe(viewLifecycleOwner) { networks ->
            pixieDustAdapter?.updateNetworks(networks)
            binding.textEmptyState.visibility = if (networks.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.isSwitchingMode.observe(viewLifecycleOwner) { switching ->
            binding.topProgressBar.visibility = if (switching) View.VISIBLE else View.GONE
        }

        viewModel.toastMessage.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                viewModel.clearToastMessage()
            }
        }

        viewModel.interfaceStatuses.observe(viewLifecycleOwner) { statuses ->
            statusAdapter?.submitList(statuses)
            if (statuses.isEmpty()) {
                binding.textNoInterfaces.visibility = View.VISIBLE
                binding.recyclerViewInterfaceStatus.visibility = View.GONE
            } else {
                binding.textNoInterfaces.visibility = View.GONE
                binding.recyclerViewInterfaceStatus.visibility = View.VISIBLE
            }
            renderInterfaceChips()
            refreshInterfaceDropdowns()
        }

        viewModel.storageItems.observe(viewLifecycleOwner) { _ -> }

        viewModel.captureTimerText.observe(viewLifecycleOwner) { text ->
            binding.textCaptureTimer.text = text
        }

        viewModel.captureProgress.observe(viewLifecycleOwner) { progress ->
            binding.progressCaptureTimer.setProgressCompat(progress.coerceIn(0, 100), true)
        }

        viewModel.idleWarning.observe(viewLifecycleOwner) { show ->
            binding.chipIdleWarning.visibility = if (show) View.VISIBLE else View.GONE
        }

        viewModel.leftoverCaptures.observe(viewLifecycleOwner) { captures ->
            if (captures != null && captures.isNotEmpty()) {
                showLeftoverCapturesDialog(captures)
            }
        }

        viewModel.leftoverImportRunning.observe(viewLifecycleOwner) { running ->
            if (running) {
                binding.topProgressBar.visibility = View.VISIBLE
            }
        }
    }

    private fun showLeftoverCapturesDialog(captures: List<AirodumpViewModel.LeftoverCapture>) {
        val count = captures.size
        val details = captures.take(10).joinToString("\n") { c ->
            val essid = c.essid ?: "?"
            val bssid = c.bssid ?: "?"
            "• $essid ($bssid)"
        }
        val message = buildString {
            appendLine(getString(R.string.airodump_leftover_message, count))
            appendLine()
            append(details)
            if (captures.size > 10) {
                appendLine()
                append(getString(R.string.airodump_leftover_more, captures.size - 10))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.airodump_leftover_title)
            .setMessage(message)
            .setPositiveButton(R.string.airodump_leftover_import) { _, _ ->
                viewModel.importLeftoverCaptures()
                Toast.makeText(
                    requireContext(),
                    R.string.airodump_leftover_importing,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.airodump_leftover_dismiss) { _, _ ->
                viewModel.dismissLeftoverCaptures()
            }
            .setCancelable(false)
            .show()
    }

    private fun showSavePrompt(req: AirodumpViewModel.SavePromptRequest) {
        if (currentStepIndex != Step.ATTACK.index) return
        val view = view ?: return
        val type =
            if (req.type == "PMKID") R.string.airodump_snackbar_pmkid_detected else R.string.airodump_snackbar_detected
        val message = getString(type, 10)
        currentSnackbar?.dismiss()
        currentSnackbar = Snackbar.make(view, message, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.airodump_save_now) { viewModel.saveNow() }
            .setActionTextColor(ContextCompat.getColor(requireContext(), R.color.success_green))
        currentSnackbar?.show()
    }

    private fun showUnverifiedSaveDialog(req: AirodumpViewModel.SavePromptRequest) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.airodump_save_unverified_title)
            .setMessage(R.string.airodump_save_unverified_message)
            .setPositiveButton(R.string.airodump_save_anyway) { _, _ ->
                viewModel.saveUnverified()
            }
            .setNegativeButton(R.string.airodump_discard) { _, _ ->
                viewModel.discardUnverified()
            }
            .setCancelable(false)
            .show()
    }

    private fun renderResults(result: CaptureResult) {
        val ctx = context ?: return
        when (result.kind) {
            CaptureResultKind.HANDSHAKE -> {
                binding.iconResult.setImageResource(R.drawable.ic_check_circle)
                binding.iconResult.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.success_green)
                )
                binding.textResultStatus.text = getString(R.string.airodump_result_handshake)
            }

            CaptureResultKind.PMKID -> {
                binding.iconResult.setImageResource(R.drawable.ic_key)
                binding.iconResult.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.success_green)
                )
                binding.textResultStatus.text = getString(R.string.airodump_result_pmkid)
            }

            CaptureResultKind.BOTH -> {
                binding.iconResult.setImageResource(R.drawable.ic_check_circle)
                binding.iconResult.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.success_green)
                )
                binding.textResultStatus.text = getString(R.string.airodump_result_both)
            }

            CaptureResultKind.PARTIAL -> {
                binding.iconResult.setImageResource(R.drawable.ic_warning)
                binding.iconResult.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.error_red)
                )
                binding.textResultStatus.text = getString(R.string.airodump_result_partial)
            }

            CaptureResultKind.NONE -> {
                binding.iconResult.setImageResource(R.drawable.ic_close)
                binding.iconResult.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.error_red)
                )
                binding.textResultStatus.text = getString(R.string.airodump_result_none)
            }
        }
        binding.textResultPath.text = result.capFilePath ?: "—"
    }

    private fun startStatusPolling() {
        statusPollingJob?.cancel()
        val ctx = requireContext()
        val intervalMs = if (ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("extended_poll_interval", false)
        ) 30000L else STATUS_POLL_INTERVAL_MS
        statusPollingJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(intervalMs)
                if (_binding != null) {
                    viewModel.pollInterfaceStatus()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        detachBackgroundCapture()
        currentSnackbar?.dismiss()
        currentSnackbar = null
        statusPollingJob?.cancel()
        statusPollingJob = null

        try {
            prefsListener?.let {
                context?.let { ctx ->
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .unregisterOnSharedPreferenceChangeListener(it)
                }
            }
        } catch (_: Exception) {
            Log.w(TAG, "Failed to unregister prefs listener")
        }
        prefsListener = null

        _binding = null
    }

    inner class InterfaceStatusAdapter(
        private val showToggle: Boolean,
        private val onToggle: (String) -> Unit
    ) : RecyclerView.Adapter<InterfaceStatusAdapter.ViewHolder>() {

        private var items: List<InterfaceStatus> = emptyList()

        fun submitList(newItems: List<InterfaceStatus>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemInterfaceStatusBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemInterfaceStatusBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.textInterfaceName.text = item.name

            if (item.subtitle != null) {
                holder.binding.textInterfaceSubtitle.text = item.subtitle
                holder.binding.textInterfaceSubtitle.visibility = View.VISIBLE
            } else {
                holder.binding.textInterfaceSubtitle.visibility = View.GONE
            }

            val modeLabel = when (item.mode) {
                IwWifiManager.MODE_MANAGED -> "MANAGED"
                IwWifiManager.MODE_MONITOR -> "MONITOR"
                IwWifiManager.MODE_UNAVAILABLE -> "UNAVAILABLE"
                else -> "UNKNOWN"
            }
            holder.binding.textInterfaceMode.text = modeLabel

            val modeColor = when (item.mode) {
                IwWifiManager.MODE_MANAGED -> android.R.color.holo_green_dark
                IwWifiManager.MODE_MONITOR -> android.R.color.holo_blue_dark
                IwWifiManager.MODE_UNAVAILABLE -> android.R.color.holo_red_dark
                else -> android.R.color.darker_gray
            }
            holder.binding.textInterfaceMode.setTextColor(
                ContextCompat.getColor(holder.binding.root.context, modeColor)
            )

            if (showToggle) {
                val isMonitor = item.mode == IwWifiManager.MODE_MONITOR
                val isUnavailable = item.mode == IwWifiManager.MODE_UNAVAILABLE
                holder.binding.buttonToggleMode.text = if (isMonitor) "Managed" else "Monitor"
                holder.binding.buttonToggleMode.visibility = View.VISIBLE
                holder.binding.buttonToggleMode.isEnabled = !isUnavailable
                holder.binding.buttonToggleMode.setOnClickListener {
                    onToggle(item.name)
                }
            } else {
                holder.binding.buttonToggleMode.visibility = View.GONE
            }
        }

        override fun getItemCount(): Int = items.size
    }

    inner class ClientsAdapter(
        private val items: List<com.lsd.wififrankenstein.util.AirodumpClient>
    ) : RecyclerView.Adapter<ClientsAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: com.lsd.wififrankenstein.databinding.ItemClientBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = com.lsd.wififrankenstein.databinding.ItemClientBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val c = items[position]
            holder.binding.textClientMac.text = c.mac
            holder.binding.textClientInfo.text =
                "PWR ${c.power} · Rate ${c.rate} · Frames ${c.frames}"
        }

        override fun getItemCount(): Int = items.size
    }
}
