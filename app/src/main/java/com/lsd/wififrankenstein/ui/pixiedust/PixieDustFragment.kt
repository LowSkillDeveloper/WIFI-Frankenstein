package com.lsd.wififrankenstein.ui.pixiedust

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentPixieDustBinding
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.ChrootType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.MacAddressUtils
import com.lsd.wififrankenstein.util.NativeWifiHelper
import com.lsd.wififrankenstein.util.PixieDustResult
import com.lsd.wififrankenstein.util.ThreeWiFiCsvRow
import com.lsd.wififrankenstein.util.ThreeWiFiUploader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PixieDustFragment : Fragment() {

    private var _binding: FragmentPixieDustBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PixieDustViewModel by activityViewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return PixieDustViewModel(requireActivity().application) as T
            }
        }
    }

    private val dbSetupViewModel: DbSetupViewModel by lazy {
        DbSetupViewModel.getInstance(requireActivity().application)
    }

    private lateinit var iwWifiManager: IwWifiManager
    private lateinit var chrootManager: ChrootManager
    private lateinit var nativeWifiHelper: NativeWifiHelper

    private var pixieDustAdapter: PixieDustAdapter? = null
    private var consoleAdapter: ConsoleAdapter? = null
    private var setupComplete = false

    private var currentInterface = "wlan0"
    private var currentAttackInterface = "wlan0"

    private var currentStepIndex = 0
    private val totalSteps = 4

    private var pendingPrefillBssid: String = ""
    private var pendingPrefillSsid: String = ""

    private var consoleVisible = true
    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? =
        null

    enum class Step(val index: Int, val titleRes: Int) {
        INTERFACE(0, R.string.pixiedust_step1_title),
        SCAN(1, R.string.pixiedust_step2_title),
        ATTACK(2, R.string.pixiedust_step3_title),
        RESULTS(3, R.string.pixiedust_step4_title)
    }

    companion object {
        private const val TAG = "PixieDustFragment"
        private const val MODE_SCAN = 0
        private const val MODE_MANUAL = 1
        private const val HANDSHAKE_PREFS = "handshake_capture"
        private const val PIXIE_PREFS = "pixie_prefs"
        private const val KEY_SCAN_IFACE = "scan_interface"
        private const val KEY_CAPTURE_IFACE = "capture_interface"
        private const val KEY_USE_NATIVE = "use_native_pixie"
        private const val STATE_STEP_INDEX = "step_index"
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.scanNetworks(currentInterface)
        } else {
            Toast.makeText(
                requireContext(),
                R.string.location_permission_required,
                Toast.LENGTH_SHORT
            ).show()
            if (_binding != null) {
                binding.buttonScan.isEnabled = true
            }
        }
    }

    private val saveConsoleLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null && consoleAdapter != null) {
            try {
                val lines = consoleAdapter!!.getLines()
                val content = lines.joinToString("\n")
                requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                Toast.makeText(requireContext(), "Console saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save console", e)
                Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPixieDustBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        iwWifiManager = IwWifiManager(requireContext())
        chrootManager = ChrootManager.get(requireContext())
        nativeWifiHelper = NativeWifiHelper(requireContext())

        binding.buttonNativePixie.setOnClickListener { enableNativeMode() }

        currentStepIndex = savedInstanceState?.getInt(STATE_STEP_INDEX, 0) ?: 0

        if (savedInstanceState != null) {
            selectedNetwork = viewModel.selectedNetwork.value
        }

        if (savedInstanceState == null) {
            pendingPrefillBssid = arguments?.getString("bssid").orEmpty()
            pendingPrefillSsid = arguments?.getString("ssid").orEmpty()
        }

        checkChrootAndSetup()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP_INDEX, currentStepIndex)
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
            getString(R.string.pixiedust_step_indicator, currentStepIndex + 1, totalSteps, title)
        _binding?.textStepIndicator1?.text = text
        _binding?.textStepIndicator2?.text = text
        _binding?.textStepIndicator3?.text = text
        _binding?.textStepIndicator4?.text = text
    }

    private fun onStepEntered(step: Step) {
        when (step) {
            Step.INTERFACE -> {}
            Step.SCAN -> {
                pixieDustAdapter?.updateNetworks(viewModel.networks.value.orEmpty())
            }

            Step.ATTACK -> {
                binding.recyclerViewConsole.post {
                    binding.recyclerViewConsole.requestLayout()
                }
                consoleAdapter?.notifyDataSetChanged()
            }

            Step.RESULTS -> {}
        }
    }

    private fun checkChrootAndSetup() {
        val chrootType = chrootManager.getChrootType()
        val hasRoot = chrootType is ChrootType.Root ||
                chrootType is ChrootType.RootMissing ||
                chrootType is ChrootType.RootWithoutChroot

        if (isNativeEnabled() && hasRoot) {
            performSetup()
            return
        }

        if (chrootType is ChrootType.None || chrootType is ChrootType.Rootless) {
            setupComplete = false
            showChrootPlaceholder(withInstall = false)
        } else if (chrootType is ChrootType.RootMissing || chrootType is ChrootType.RootWithoutChroot) {
            setupComplete = false
            showChrootPlaceholder(withInstall = true)
        } else if (!setupComplete) {
            performSetup()
        }
    }

    private fun performSetup() {
        if (setupComplete) return
        setupComplete = true
        showSteps()
        loadWifiInterfaces()
        setupRecyclerView()
        setupTabLayout()
        setupButtons()
        setupConsole()
        setupAttackSettings()
        registerPrefsListener()
        observeViewModel()
        viewModel.startModePolling()

        binding.viewFlipperSteps.displayedChild = currentStepIndex
        updateStepIndicators()
        applyPrefillFromArguments()
    }

    private fun applyPrefillFromArguments() {
        if (_binding == null || pendingPrefillBssid.isBlank()) return
        val bssid = pendingPrefillBssid.uppercase()
        binding.tabLayout.getTabAt(MODE_MANUAL)?.select()
        binding.editTextBssid.setText(bssid)
        goToStep(Step.SCAN)
    }

    private fun isNativeEnabled(): Boolean {
        return requireContext().getSharedPreferences(PIXIE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_NATIVE, false)
    }

    private fun enableNativeMode() {
        requireContext().getSharedPreferences(PIXIE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_NATIVE, true).apply()
        checkChrootAndSetup()
    }

    private fun showSteps() {
        _binding?.placeholderChroot?.visibility = View.GONE
        _binding?.viewFlipperSteps?.visibility = View.VISIBLE
    }

    private fun showChrootPlaceholder(withInstall: Boolean) {
        val b = _binding ?: return
        b.placeholderChroot.visibility = View.VISIBLE
        b.viewFlipperSteps.visibility = View.GONE
        b.progressBar.visibility = View.GONE

        b.textViewChrootTitle.text = getString(R.string.menu_pixiedust)

        if (withInstall) {
            b.textViewChrootMessage.text = getString(R.string.chroot_not_installed)
            b.buttonInstallChroot.text = getString(R.string.install_chroot)
            b.buttonInstallChroot.setOnClickListener { startChrootInstallation() }
            b.buttonNativePixie.visibility = View.VISIBLE
        } else {
            b.textViewChrootMessage.text = getString(R.string.pixiedust_root_required)
            b.buttonInstallChroot.text = getString(R.string.go_back)
            b.buttonInstallChroot.setOnClickListener { findNavController().navigateUp() }
            b.buttonNativePixie.visibility = View.GONE
        }
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
            }
        }
    }

    private fun setupAttackSettings() {
        val prefs = requireContext().getSharedPreferences("pixie_prefs", Context.MODE_PRIVATE)
        binding.switchDisableWifi.isChecked = prefs.getBoolean("disable_wifi_before_attack", true)
        binding.switchDisableWifi.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("disable_wifi_before_attack", isChecked).apply()
        }
        binding.switchDetailedLogging.isChecked = prefs.getBoolean("detailed_logging", false)
        binding.switchDetailedLogging.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("detailed_logging", isChecked).apply()
        }
        binding.switchUsePinGenerator.isChecked = prefs.getBoolean("use_pin_generator", false)
        binding.switchUsePinGenerator.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_pin_generator", isChecked).apply()
        }
        val chrootAvailable = chrootManager.getChrootType() is ChrootType.Root
        val useNative = !chrootAvailable
        prefs.edit().putBoolean("use_native_pixie", useNative).apply()
        binding.toggleModeGroup.check(if (useNative) R.id.modeRoot else R.id.modeChroot)
        binding.toggleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val nativeSelected = checkedId == R.id.modeRoot
            prefs.edit().putBoolean("use_native_pixie", nativeSelected).apply()
            if (nativeSelected && !setupComplete) {
                checkChrootAndSetup()
            }
        }
        updateModeToggleAvailability()
    }

    private fun updateModeToggleAvailability() {
        if (_binding == null) return
        val chrootAvailable = chrootManager.getChrootType() is ChrootType.Root
        binding.modeChroot.isEnabled = chrootAvailable
        binding.modeChroot.alpha = if (chrootAvailable) 1f else 0.45f
        if (!chrootAvailable && binding.modeChroot.isChecked) {
            binding.toggleModeGroup.check(R.id.modeRoot)
        }
    }

    private fun setupButtons() {
        binding.buttonRefresh.setOnClickListener { loadWifiInterfaces() }
        binding.buttonSwitchScanManaged.setOnClickListener { switchScanToManaged() }
        binding.buttonSwitchAttackManaged.setOnClickListener { switchAttackToManaged() }

        binding.buttonContinueToScan.setOnClickListener {
            goToStep(Step.SCAN)
        }
        binding.buttonBackToStep1.setOnClickListener { goToStep(Step.INTERFACE, forward = false) }
        binding.buttonBackToStep2.setOnClickListener { goToStep(Step.SCAN, forward = false) }
        binding.buttonBackToScan.setOnClickListener { goToStep(Step.SCAN, forward = false) }

        binding.buttonScan.setOnClickListener { scanNetworks() }

        binding.buttonManualAttack.setOnClickListener {
            if (viewModel.isAttackRunning.value == true) return@setOnClickListener
            val bssidInput = binding.editTextBssid.text?.toString()?.trim() ?: ""
            val normalized = normalizeBssid(bssidInput)
            if (normalized == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.pixiedust_invalid_bssid_format,
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val network = IwWifiNetwork(
                ssid = "",
                bssid = normalized,
                frequency = "",
                channel = "",
                signal = "",
                lastSeen = "",
                beaconInterval = "",
                capabilities = "",
                wpsEnabled = true,
                wpsLocked = false,
                rawData = ""
            )
            selectNetwork(network)
            startAttack(network)
        }

        binding.buttonStopAttack.setOnClickListener {
            if (viewModel.isAttackRunning.value != true) return@setOnClickListener
            stopAttack()
        }

        binding.buttonCopyResults.setOnClickListener {
            copyResultsToClipboard()
        }

        binding.buttonStartNewScan.setOnClickListener {
            goToStep(Step.SCAN, forward = false)
        }

        binding.buttonRetry.setOnClickListener {
            startAttack(selectedNetwork)
        }

        binding.buttonNewTarget.setOnClickListener {
            binding.cardFailureResult.visibility = View.GONE
            goToStep(Step.SCAN, forward = false)
        }
    }

    private fun setupConsole() {
        consoleAdapter = ConsoleAdapter(autoScroll = true)
        binding.recyclerViewConsole.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = consoleAdapter
        }
        consoleAdapter?.attachToRecyclerView(binding.recyclerViewConsole)

        binding.iconToggleConsole.setOnClickListener { toggleConsoleVisibility() }
        binding.layoutConsoleHeader.setOnClickListener { toggleConsoleVisibility() }
        binding.iconCopyConsole.setOnClickListener {
            val lines = consoleAdapter?.getLines() ?: emptyList()
            if (lines.isNotEmpty()) {
                val text = lines.joinToString("\n")
                val clip = android.content.ClipData.newPlainText("Console Log", text)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE).let {
                    (it as ClipboardManager).setPrimaryClip(clip)
                }
                Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT)
                    .show()
            }
        }
        binding.iconSaveConsole.setOnClickListener {
            saveConsoleLauncher.launch("pixiedust_console.txt")
        }
    }

    private fun toggleConsoleVisibility() {
        consoleVisible = !consoleVisible
        if (consoleVisible) {
            binding.recyclerViewConsole.visibility = View.VISIBLE
            binding.iconToggleConsole.setImageResource(R.drawable.ic_expand_less)
        } else {
            binding.recyclerViewConsole.visibility = View.GONE
            binding.iconToggleConsole.setImageResource(R.drawable.ic_expand_more)
        }
    }

    private fun setupTabLayout() {
        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(
            binding.tabLayout.newTab().setText(getString(R.string.pixiedust_scan_mode))
        )
        binding.tabLayout.addTab(
            binding.tabLayout.newTab().setText(getString(R.string.pixiedust_manual_mode))
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
    }

    private fun showManualMode() {
        binding.cardManualInput.visibility = View.VISIBLE
        binding.recyclerViewNetworks.visibility = View.GONE
        binding.textEmptyState.visibility = View.GONE
        binding.buttonScan.isEnabled = false
    }

    private fun setupRecyclerView() {
        pixieDustAdapter = PixieDustAdapter { network ->
            selectNetwork(network)
        }
        binding.recyclerViewNetworks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = pixieDustAdapter
        }
    }

    private fun scanNetworks() {
        val iface = currentInterface
        binding.textEmptyState.visibility = View.GONE
        binding.buttonScan.isEnabled = false

        selectedNetwork = null

        viewModel.clearConsole()
        viewModel.scanNetworks(iface)
    }

    private var selectedNetwork: IwWifiNetwork? = null

    private fun selectNetwork(network: IwWifiNetwork) {
        selectedNetwork = network
        viewModel.selectNetwork(network)
        showTargetConfirmDialog(network)
    }

    private fun showTargetConfirmDialog(network: IwWifiNetwork) {
        val ssid = network.ssid.ifEmpty { getString(R.string.pixiedust_hidden_network) }
        val message = buildString {
            appendLine("SSID: $ssid")
            appendLine("BSSID: ${network.bssid}")
            if (network.signal.isNotEmpty()) {
                appendLine("Signal: ${network.signal}")
            }
            if (network.channel.isNotEmpty()) {
                appendLine("Channel: ${network.channel}")
            }
            appendLine()
            append(getString(R.string.pixiedust_start_attack))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pixiedust_target_info)
            .setMessage(message)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.pixiedust_start_attack) { _, _ ->
                startAttack(network)
            }
            .show()
    }

    private fun startAttack(network: IwWifiNetwork?) {
        binding.cardFailureResult.visibility = View.GONE
        val net = network ?: selectedNetwork ?: run {
            Toast.makeText(requireContext(), "No network selected", Toast.LENGTH_SHORT).show()
            return
        }
        val bssid = net.bssid.uppercase()
        if (bssid.isEmpty()) {
            Toast.makeText(requireContext(), R.string.invalid_bssid, Toast.LENGTH_SHORT).show()
            return
        }

        val iface = currentAttackInterface
        val prefs = requireContext().getSharedPreferences("pixie_prefs", Context.MODE_PRIVATE)
        val disableWifi = prefs.getBoolean("disable_wifi_before_attack", true)
        val usePinGenerator = prefs.getBoolean("use_pin_generator", false)
        val useNative = prefs.getBoolean("use_native_pixie", false)

        binding.textAttackTargetSsid.text =
            net.ssid.ifEmpty { getString(R.string.pixiedust_hidden_network) }
        binding.textAttackTargetInfo.text = bssid

        viewModel.startAttack(
            bssid = bssid,
            iface = iface,
            disableWifi = disableWifi,
            usePinGenerator = usePinGenerator,
            useNative = useNative,
            dbSetupViewModel = dbSetupViewModel
        )

        goToStep(Step.ATTACK)
    }

    private fun stopAttack() {
        viewModel.stopAttack()
        binding.progressBar.setColor(android.graphics.Color.RED)
    }

    private fun observeViewModel() {
        viewModel.consoleLines.observe(viewLifecycleOwner) { lines ->
            consoleAdapter?.setLines(lines)
        }

        viewModel.networks.observe(viewLifecycleOwner) { networks ->
            pixieDustAdapter?.updateNetworks(networks)
            binding.textEmptyState.visibility = if (networks.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
            binding.progressSmall.visibility = if (scanning) View.VISIBLE else View.GONE
            binding.buttonScan.isEnabled = !scanning
        }

        viewModel.needsLocationPermission.observe(viewLifecycleOwner) { needed ->
            if (!needed) return@observe
            viewModel.clearNeedsLocationPermission()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    viewModel.scanNetworks(currentInterface)
                }
            }
        }

        viewModel.isAttackRunning.observe(viewLifecycleOwner) { running ->
            if (running) {
                binding.progressBar.setColor(
                    ContextCompat.getColor(requireContext(), R.color.colorPrimary)
                )
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.startAnimation()
                binding.buttonStopAttack.visibility = View.VISIBLE
                binding.buttonStopAttack.isEnabled = true
                binding.buttonManualAttack.isEnabled = false
                consoleVisible = true
                binding.iconToggleConsole.setImageResource(R.drawable.ic_expand_less)
                binding.recyclerViewConsole.visibility = View.VISIBLE
            } else {
                binding.progressBar.stopAnimation()
                binding.progressBar.visibility = View.GONE
                binding.buttonStopAttack.visibility = View.GONE
                binding.buttonManualAttack.isEnabled = true
            }
        }

        viewModel.pixieDone.observe(viewLifecycleOwner) { done ->
            if (done) {
                binding.buttonStopAttack.visibility = View.GONE
            }
        }

        viewModel.attackResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                if (result.success && result.wpsPin != null) {
                    if (currentStepIndex != Step.RESULTS.index) {
                        goToStep(Step.RESULTS, forward = true)
                    }
                }
                renderResults(result)
                viewModel.clearAttackResult()
            }
        }

        viewModel.statusText.observe(viewLifecycleOwner) { status ->
            binding.textStatus.text = status
        }

        viewModel.toastMessage.observe(viewLifecycleOwner) { message ->
            if (message != null) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                viewModel.clearToastMessage()
            }
        }

        viewModel.scanMode.observe(viewLifecycleOwner) { mode ->
            updateModeUi(
                currentInterface,
                mode,
                binding.textScanMode,
                binding.buttonSwitchScanManaged
            )
        }

        viewModel.attackMode.observe(viewLifecycleOwner) { mode ->
            updateModeUi(
                currentAttackInterface,
                mode,
                binding.textAttackMode,
                binding.buttonSwitchAttackManaged
            )
        }
    }

    @androidx.annotation.ColorInt
    private fun colorStateListOf(@androidx.annotation.ColorInt color: Int): android.content.res.ColorStateList {
        if (android.os.Build.VERSION.SDK_INT >= 22) {
            return android.content.res.ColorStateList.valueOf(color)
        }
        return android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled)),
            intArrayOf(color)
        )
    }

    private fun renderResults(result: PixieDustResult) {
        val ctx = context ?: return
        val isTimeout = result.rawOutput.contains("TIMEOUT")

        if (result.success && result.wpsPin != null) {
            binding.cardFailureResult.visibility = View.GONE
            binding.iconResult.setImageResource(R.drawable.ic_check_circle)
            binding.iconResult.imageTintList = colorStateListOf(
                ContextCompat.getColor(ctx, R.color.success_green)
            )
            binding.textResultStatus.text = getString(R.string.pixiedust_attack_success)

            val net = selectedNetwork
            val essid = net?.ssid ?: ""
            val bssid = net?.bssid ?: ""
            val pin = result.wpsPin
            val psk = result.wpaPsk ?: getString(R.string.no_psk_found)
            binding.cardResults.visibility = View.VISIBLE
            binding.textResultsData.text =
                "ESSID: ${essid.ifEmpty { getString(R.string.pixiedust_hidden_network) }}\nBSSID: $bssid\nPIN: $pin\nPSK: $psk"

            showResultDialog(
                success = true,
                wpsPin = result.wpsPin,
                wpaPsk = result.wpaPsk,
                essid = essid,
                bssid = bssid,
                failureMessage = null
            )
        } else {
            val consoleLines = consoleAdapter?.getLines() ?: emptyList()
            val failureMessage = result.reason?.takeIf { it.isNotBlank() }
                ?: if (isTimeout) {
                    getString(R.string.pixiedust_timeout_failure)
                } else {
                    extractFailureMessage(consoleLines)
                }

            binding.cardFailureResult.visibility = View.VISIBLE
            binding.textFailureReason.text = failureMessage
        }
    }

    private fun showResultDialog(
        success: Boolean,
        wpsPin: String?,
        wpaPsk: String?,
        essid: String,
        bssid: String,
        failureMessage: String?
    ) {
        if (_binding == null) return

        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_pixie_result, null)

        val textDialogTitle = dialogView.findViewById<TextView>(R.id.textDialogTitle)
        val layoutSuccessFields = dialogView.findViewById<LinearLayout>(R.id.layoutSuccessFields)
        val textFailureMessage = dialogView.findViewById<TextView>(R.id.textFailureMessage)

        if (success && wpsPin != null) {
            textDialogTitle.text = getString(R.string.pixiedust_attack_success)
            layoutSuccessFields.visibility = View.VISIBLE
            textFailureMessage.visibility = View.GONE

            dialogView.findViewById<TextView>(R.id.textDialogEssid).text = essid
            dialogView.findViewById<TextView>(R.id.textDialogBssid).text = bssid
            dialogView.findViewById<TextView>(R.id.textDialogWpsPin).text = wpsPin
            dialogView.findViewById<TextView>(R.id.textDialogWpaPsk).text =
                wpaPsk ?: getString(R.string.no_psk_found)

            dialogView.findViewById<ImageButton>(R.id.buttonCopyWpsPin).setOnClickListener {
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WPS PIN", wpsPin))
                Toast.makeText(
                    requireContext(),
                    getString(R.string.copied_to_clipboard, "WPS PIN"),
                    Toast.LENGTH_SHORT
                ).show()
            }
            dialogView.findViewById<ImageButton>(R.id.buttonCopyWpaPsk).setOnClickListener {
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "WPA PSK",
                        wpaPsk ?: ""
                    )
                )
                Toast.makeText(
                    requireContext(),
                    getString(R.string.copied_to_clipboard, "WPA PSK"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            textDialogTitle.text = getString(R.string.pixiedust_attack_failed)
            layoutSuccessFields.visibility = View.GONE
            textFailureMessage.visibility = View.VISIBLE
            textFailureMessage.text = failureMessage ?: "WPS pin not found!"
        }

        val buttonUpload3wifi =
            dialogView.findViewById<MaterialButton>(R.id.buttonUpload3wifi)
        if (success && wpsPin != null && bssid.isNotEmpty()) {
            buttonUpload3wifi.visibility = View.VISIBLE
            buttonUpload3wifi.setOnClickListener {
                uploadPixieTo3WiFi(essid, bssid, wpaPsk, wpsPin)
            }
        } else {
            buttonUpload3wifi.visibility = View.GONE
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(null)
            .setView(dialogView)
            .setPositiveButton(R.string.ok) { _, _ -> }
            .show()
    }

    private fun copyResultsToClipboard() {
        val data = binding.textResultsData.text?.toString() ?: ""
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PixieDust Results", data))
        Toast.makeText(
            requireContext(),
            getString(R.string.copied_to_clipboard, "Results"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun extractFailureMessage(consoleLines: List<String>): String {
        for (line in consoleLines.reversed()) {
            val trimmed = line.trim()
            if (trimmed.contains("WPS pin not found", ignoreCase = true) ||
                trimmed.contains("WPS PIN not found", ignoreCase = true) ||
                trimmed.contains("pin not found", ignoreCase = true)
            ) {
                return getString(R.string.pixiedust_fail_not_vulnerable)
            }
            if (trimmed.contains("Timeout", ignoreCase = true) ||
                trimmed.contains("timeout", ignoreCase = true)
            ) {
                return "Timeout, maybe WPS lock or WPS function disabled."
            }
            if (trimmed.startsWith("[-] Reason:")) {
                return trimmed.removePrefix("[-] Reason:").trim()
            }
            if (trimmed.contains("[-]", ignoreCase = true) && trimmed.isNotEmpty()) {
                return trimmed
            }
        }
        return getString(R.string.pixiedust_fail_not_vulnerable)
    }

    private fun normalizeBssid(input: String): String? {
        val normalized = MacAddressUtils.formatToColonSeparated(input)
        if (normalized == null || normalized.length < 17) return null
        return normalized.uppercase()
    }

    private fun loadWifiInterfaces() {
        binding.progressSmall.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ifaces = if (isNativeEnabled()) {
                    nativeWifiHelper.ensureReady()
                    nativeWifiHelper.getAvailableInterfaces()
                } else {
                    iwWifiManager.getAvailableInterfaces()
                }
                val names = ifaces.map { it.name }.toTypedArray()
                val prefs =
                    requireContext().getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)

                val savedScanIface =
                    prefs.getString(KEY_SCAN_IFACE, names.getOrNull(0) ?: "wlan0")!!
                currentInterface =
                    validateInterface(savedScanIface, names, names.firstOrNull() ?: "wlan0")

                val savedAttackIface =
                    prefs.getString(KEY_CAPTURE_IFACE, names.getOrNull(0) ?: "wlan0")!!
                currentAttackInterface =
                    validateInterface(savedAttackIface, names, names.firstOrNull() ?: "wlan0")

                setupDropdown(binding.autoCompleteScanInterface, names, currentInterface)
                setupDropdown(binding.autoCompleteAttackInterface, names, currentAttackInterface)

                attachDropdownListeners(prefs)

                viewModel.setScanInterface(currentInterface)
                viewModel.setAttackInterface(currentAttackInterface)
                viewModel.checkScanMode(currentInterface)
                viewModel.checkAttackMode(currentAttackInterface)
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Log.e(TAG, "Failed to load interfaces", e)
                currentInterface = "wlan0"
                currentAttackInterface = "wlan0"
                setupDropdown(binding.autoCompleteScanInterface, arrayOf("wlan0"), "wlan0")
                setupDropdown(binding.autoCompleteAttackInterface, arrayOf("wlan0"), "wlan0")
            } finally {
                if (_binding != null) {
                    binding.progressSmall.visibility = View.GONE
                }
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

    private fun attachDropdownListeners(prefs: android.content.SharedPreferences) {
        binding.autoCompleteScanInterface.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val iface =
                    (parent?.getItemAtPosition(position) as? String) ?: return@OnItemClickListener
                if (iface != currentInterface) {
                    currentInterface = iface
                    prefs.edit().putString(KEY_SCAN_IFACE, currentInterface).apply()
                    viewModel.setScanInterface(currentInterface)
                    viewModel.checkScanMode(currentInterface)
                }
            }

        binding.autoCompleteAttackInterface.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val iface =
                    (parent?.getItemAtPosition(position) as? String) ?: return@OnItemClickListener
                if (iface != currentAttackInterface) {
                    currentAttackInterface = iface
                    prefs.edit().putString(KEY_CAPTURE_IFACE, currentAttackInterface).apply()
                    viewModel.setAttackInterface(currentAttackInterface)
                    viewModel.checkAttackMode(currentAttackInterface)
                }
            }
    }

    private fun registerPrefsListener() {
        val prefs = requireContext().getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
        prefsListener?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        prefsListener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (_binding == null) return@OnSharedPreferenceChangeListener
                when (key) {
                    KEY_SCAN_IFACE -> {
                        val newIface = prefs.getString(key, "wlan0") ?: "wlan0"
                        if (newIface != currentInterface) {
                            currentInterface = newIface
                            binding.autoCompleteScanInterface.setText(newIface, false)
                            viewModel.checkScanMode(newIface)
                        }
                    }

                    KEY_CAPTURE_IFACE -> {
                        val newIface = prefs.getString(key, "wlan0") ?: "wlan0"
                        if (newIface != currentAttackInterface) {
                            currentAttackInterface = newIface
                            binding.autoCompleteAttackInterface.setText(newIface, false)
                            viewModel.checkAttackMode(newIface)
                        }
                    }
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun switchScanToManaged() {
        viewModel.setInterfaceMode(currentInterface, IwWifiManager.MODE_MANAGED)
    }

    private fun switchAttackToManaged() {
        viewModel.setInterfaceMode(currentAttackInterface, IwWifiManager.MODE_MANAGED)
    }

    private fun updateModeUi(
        iface: String,
        mode: String,
        modeText: TextView,
        switchBtn: MaterialButton
    ) {
        val label = when (mode) {
            IwWifiManager.MODE_MANAGED -> "MANAGED"
            IwWifiManager.MODE_MONITOR -> "MONITOR"
            else -> mode
        }
        val color = when (mode) {
            IwWifiManager.MODE_MANAGED -> android.graphics.Color.rgb(76, 175, 80)
            IwWifiManager.MODE_MONITOR -> android.graphics.Color.rgb(33, 150, 243)
            IwWifiManager.MODE_UNAVAILABLE -> android.graphics.Color.rgb(244, 67, 54)
            else -> android.graphics.Color.GRAY
        }
        modeText.text = label
        modeText.setTextColor(color)
        switchBtn.visibility = if (isNativeEnabled()) {
            View.GONE
        } else if (mode == IwWifiManager.MODE_MONITOR) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun uploadPixieTo3WiFi(essid: String, bssid: String, psk: String?, wpsPin: String?) {
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
                val row = ThreeWiFiCsvRow(
                    bssid = bssid,
                    essid = essid,
                    key = psk ?: "",
                    wps = wpsPin ?: "",
                )
                val csv = ThreeWiFiUploader.convertToCsv(listOf(row))
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = ThreeWiFiUploader.uploadCsv(server, csv)
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

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopModePolling()
        try {
            prefsListener?.let {
                context?.let { ctx ->
                    ctx.getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)
                        .unregisterOnSharedPreferenceChangeListener(it)
                }
            }
        } catch (_: Exception) {
        }
        prefsListener = null
        _binding = null
    }
}
