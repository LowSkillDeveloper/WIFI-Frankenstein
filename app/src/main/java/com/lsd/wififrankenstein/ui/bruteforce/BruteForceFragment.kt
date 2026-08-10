package com.lsd.wififrankenstein.ui.bruteforce

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentBruteforceBinding
import com.lsd.wififrankenstein.service.ForegroundAttackService
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork
import com.lsd.wififrankenstein.ui.pixiedust.ConsoleAdapter
import com.lsd.wififrankenstein.ui.pixiedust.PixieDustAdapter
import com.lsd.wififrankenstein.ui.settings.WlanInterfaceManagerViewModel
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.MacAddressUtils
import com.lsd.wififrankenstein.util.NativePskBruteForceRunner
import com.lsd.wififrankenstein.util.NativeWifiHelper
import com.lsd.wififrankenstein.util.PskBruteForceEngines
import com.lsd.wififrankenstein.util.PskBruteForceResult
import com.lsd.wififrankenstein.util.RootlessManager
import com.lsd.wififrankenstein.util.WpsBruteForceRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class AttackMode { WPS_BRUTE, PSK_BRUTE }

enum class PskEngine { NATIVE, CHROOT }

class BruteForceFragment : Fragment() {
    private val TAG = "BruteForceFragment"

    private var _binding: FragmentBruteforceBinding? = null
    private val binding get() = _binding!!

    private lateinit var iwWifiManager: IwWifiManager
    private lateinit var wlanInterfaceViewModel: WlanInterfaceManagerViewModel
    private val chrootManager by lazy { ChrootManager(requireContext()) }
    private val rootlessManager by lazy { RootlessManager(requireContext()) }
    private val nativeWifiHelper by lazy { NativeWifiHelper(requireContext()) }

    private val isChrootEnvReady: Boolean
        get() {
            val type = chrootManager.getChrootType()
            return type is com.lsd.wififrankenstein.util.ChrootType.Root ||
                    (type is com.lsd.wififrankenstein.util.ChrootType.Rootless && rootlessManager.isSetupCompleted())
        }

    private var currentStepIndex = 0
    private var attackMode: AttackMode? = null
    private var selectedNetwork: IwWifiNetwork? = null
    private var selectedWordlistUri: Uri? = null
    private var selectedWordlistLabel: String? = null
    private var isAttackRunning = false
    private var consoleAdapter: ConsoleAdapter? = null
    private var currentRunner: WpsBruteForceRunner? = null
    private var nativeAttackJob: Job? = null
    private var currentInterface = "wlan0"
    private var selectedPskEngine: PskEngine? = null
    private var wpsChrootBadge: TextView? = null

    private val attackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ForegroundAttackService.BROADCAST_ATTACK_COMPLETE -> {
                    val psk = intent.getStringExtra(ForegroundAttackService.EXTRA_RESULT_PSK)
                    showBackgroundResult(
                        if (!psk.isNullOrBlank()) getString(R.string.psk_found_toast, psk)
                        else getString(R.string.psk_not_found_toast)
                    )
                }

                ForegroundAttackService.BROADCAST_ATTACK_ERROR -> {
                    val msg = intent.getStringExtra(ForegroundAttackService.EXTRA_ERROR_MESSAGE)
                        ?: "Error"
                    showBackgroundResult(getString(R.string.psk_attack_error_toast, msg))
                }
            }
        }
    }

    private var pendingArgSsid: String? = null
    private var pendingArgBssid: String? = null
    private var pendingArgInterface: String? = null
    private var pendingArgEngine: String? = null

    private enum class Step(val index: Int) {
        SELECT_TYPE(0),
        TARGET(1),
        ATTACK(2),
        RESULTS(3)
    }

    private val pixieDustAdapter = PixieDustAdapter { network ->
        selectedNetwork = network
        updateTargetInfo(network)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBruteforceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        iwWifiManager = IwWifiManager(requireContext())
        wlanInterfaceViewModel =
            ViewModelProvider(requireActivity()).get(WlanInterfaceManagerViewModel::class.java)

        pendingArgSsid = arguments?.getString("ssid")
        pendingArgBssid = arguments?.getString("bssid")
        pendingArgInterface = arguments?.getString("interface")
        pendingArgEngine = arguments?.getString("engine")

        currentStepIndex = savedInstanceState?.getInt("step_index", 0) ?: 0

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            attackReceiver,
            IntentFilter().apply {
                addAction(ForegroundAttackService.BROADCAST_ATTACK_COMPLETE)
                addAction(ForegroundAttackService.BROADCAST_ATTACK_ERROR)
            }
        )

        checkChrootAndSetup()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step_index", currentStepIndex)
    }

    private fun checkChrootAndSetup() {
        binding.placeholderChroot.visibility = View.GONE
        binding.viewFlipperSteps.visibility = View.VISIBLE
        setupViews()
        updateChrootDependentCards(isChrootEnvReady)
        binding.viewFlipperSteps.displayedChild = currentStepIndex
        updateStepIndicators()
        onStepEntered(Step.entries[currentStepIndex])
        applyPrefillFromArguments()
    }

    private fun applyPrefillFromArguments() {
        val argSsid = pendingArgSsid
        val argBssid = pendingArgBssid
        pendingArgSsid = null
        pendingArgBssid = null

        pendingArgEngine?.let {
            selectedPskEngine = PskEngine.values().find { engine -> engine.name == it }
            pendingArgEngine = null
        }

        if (argSsid.isNullOrBlank() || argBssid.isNullOrBlank()) return

        attackMode = AttackMode.PSK_BRUTE
        binding.cardSelectPskBrute.strokeWidth = 3
        binding.cardSelectWpsBrute.strokeWidth = 1

        if (currentStepIndex == Step.SELECT_TYPE.index) {
            goToStep(Step.TARGET)
        }

        binding.tabLayout.getTabAt(1)?.select()
        binding.editTextSsid.setText(argSsid)
        binding.editTextBssid.setText(argBssid)

        if (currentStepIndex == Step.TARGET.index && attackMode == AttackMode.PSK_BRUTE) {
            setupPskEngineSelector()
        }
    }

    private fun updateChrootDependentCards(ready: Boolean) {

        val alpha = if (ready) 1f else 0.4f
        binding.cardSelectWpsBrute.alpha = alpha
        binding.cardSelectWpsBrute.isClickable = ready
        binding.cardSelectWpsBrute.isFocusable = ready

        binding.cardSelectPskBrute.alpha = 1f
        binding.cardSelectPskBrute.isClickable = true
        binding.cardSelectPskBrute.isFocusable = true
        binding.cardSelectWpaCracker.alpha = 1f
        binding.cardSelectWpaCracker.isClickable = true
        binding.cardSelectWpaCracker.isFocusable = true
        updateWpsChrootBadge(show = !ready)
    }

    private fun updateWpsChrootBadge(show: Boolean) {
        val card = binding.cardSelectWpsBrute
        val existing = wpsChrootBadge
        if (show && existing == null) {
            val badge = TextView(requireContext()).apply {
                text = getString(R.string.drawer_badge_chroot)
                textSize = 10f
                setTextColor(Color.WHITE)
                val bg = GradientDrawable().apply {
                    setColor(
                        ContextCompat.getColor(
                            requireContext(),
                            R.color.drawer_badge_chroot_bg
                        )
                    )
                    cornerRadius = (12 * resources.displayMetrics.density)
                }
                background = bg
                setPadding(
                    (8 * resources.displayMetrics.density).toInt(),
                    (3 * resources.displayMetrics.density).toInt(),
                    (8 * resources.displayMetrics.density).toInt(),
                    (3 * resources.displayMetrics.density).toInt()
                )
            }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
            card.addView(badge, params)
            wpsChrootBadge = badge
        } else if (!show && existing != null) {
            card.removeView(existing)
            wpsChrootBadge = null
        }
    }

    private fun showChrootPlaceholder(withInstall: Boolean) {
        binding.viewFlipperSteps.visibility = View.GONE
        binding.placeholderChroot.visibility = View.VISIBLE
        if (withInstall) {
            binding.textViewChrootMessage.text = getString(R.string.chroot_not_installed)
            binding.buttonInstallChroot.text = getString(R.string.install_chroot)
            binding.buttonInstallChroot.visibility = View.VISIBLE
            binding.buttonInstallChroot.setOnClickListener { startChrootInstallation() }
        } else {
            binding.textViewChrootMessage.text = getString(R.string.pixiedust_root_required)
            binding.buttonInstallChroot.text = getString(R.string.go_back)
            binding.buttonInstallChroot.visibility = View.VISIBLE
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
                    onProgress = { p ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            binding.textViewChrootStatus.text =
                                "${getString(R.string.chroot_installing)} $p%"
                        }
                    },
                    onStatusUpdate = { s ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                            binding.textViewChrootStatus.text = s
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "downloadAndInstall failed", e)
                false
            }
            if (_binding == null) return@launch
            binding.progressBarChrootInstall.visibility = View.GONE
            binding.buttonInstallChroot.isEnabled = true
            if (success) {
                binding.textViewChrootStatus.text =
                    getString(R.string.chroot_installed_success); checkChrootAndSetup()
            }
        }
    }

    private fun setupViews() {
        binding.cardSelectWpsBrute.setOnClickListener { selectAttackMode(AttackMode.WPS_BRUTE) }
        binding.cardSelectPskBrute.setOnClickListener { selectAttackMode(AttackMode.PSK_BRUTE) }
        binding.cardSelectWpaCracker.setOnClickListener {
            findNavController().navigate(R.id.nav_wpa_cracker)
        }
        binding.buttonBackToStep0.setOnClickListener { goToStep(Step.SELECT_TYPE, forward = false) }
        binding.buttonBackToStep1.setOnClickListener { goToStep(Step.TARGET, forward = false) }
        binding.buttonBackToStart.setOnClickListener { goToStep(Step.SELECT_TYPE, forward = false) }
        binding.buttonStartAttack.setOnClickListener { proceedToAttack() }

        binding.tabLayout.addTab(
            binding.tabLayout.newTab().setText(R.string.handshake_scan_networks)
        )
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.manual_input))
        binding.tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showScanMode(); 1 -> showManualMode()
                }
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        binding.buttonScan.setOnClickListener { scanNetworks() }
        binding.recyclerViewNetworks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = pixieDustAdapter
        }

        binding.layoutConsoleHeader.setOnClickListener { toggleConsole() }
        binding.buttonCancelAttack.setOnClickListener { cancelAttack() }
        binding.buttonRunBackground.setOnClickListener { runInBackground() }

        loadInterfaces()
    }

    private fun getInterfaceFromDropdown(): String {
        return binding.autoCompleteBruteInterface.text?.toString()?.takeIf { it.isNotEmpty() }
            ?: "wlan0"
    }

    private fun selectAttackMode(mode: AttackMode) {
        if (mode == AttackMode.WPS_BRUTE && !isChrootEnvReady) {
            Toast.makeText(requireContext(), R.string.bruteforce_chroot_required, Toast.LENGTH_LONG)
                .show()
            return
        }
        attackMode = mode
        binding.cardSelectWpsBrute.strokeWidth = if (mode == AttackMode.WPS_BRUTE) 3 else 1
        binding.cardSelectPskBrute.strokeWidth = if (mode == AttackMode.PSK_BRUTE) 3 else 1
        goToStep(Step.TARGET)
    }

    private fun goToStep(step: Step, forward: Boolean = step.index > currentStepIndex) {
        currentStepIndex = step.index
        binding.viewFlipperSteps.inAnimation = AnimationUtils.loadAnimation(
            requireContext(),
            if (forward) R.anim.slide_in_right else R.anim.slide_in_left
        )
        binding.viewFlipperSteps.outAnimation = AnimationUtils.loadAnimation(
            requireContext(),
            if (forward) R.anim.slide_out_left else R.anim.slide_out_right
        )
        binding.viewFlipperSteps.displayedChild = step.index
        updateStepIndicators()
        onStepEntered(step)
    }

    private fun updateStepIndicators() {
        val stepTitles = listOf(
            R.string.bruteforce_step0_title,
            R.string.bruteforce_step1_title,
            R.string.bruteforce_step2_title,
            R.string.bruteforce_step3_title
        )
        val indicators = listOf(
            binding.textStepIndicator0, binding.textStepIndicator1,
            binding.textStepIndicator2, binding.textStepIndicator3
        )
        indicators.forEachIndexed { i, tv ->
            tv.text = getString(
                R.string.handshake_step_format,
                i + 1,
                stepTitles.size,
                getString(stepTitles[i])
            )
        }
    }

    private fun onStepEntered(step: Step) {
        when (step) {
            Step.SELECT_TYPE -> {}
            Step.TARGET -> {
                updateTargetInfo(selectedNetwork)
                val isPsk = attackMode == AttackMode.PSK_BRUTE
                binding.cardEngineSelector.visibility =
                    if (isPsk) View.VISIBLE else View.GONE
                if (isPsk) setupPskEngineSelector()
            }

            Step.ATTACK -> {}
            Step.RESULTS -> {}
        }
    }

    private fun showScanMode() {
        binding.layoutTargetInfo.visibility =
            if (selectedNetwork != null) View.VISIBLE else View.GONE
        binding.recyclerViewNetworks.visibility = View.VISIBLE
        binding.cardManualInput.visibility = View.GONE
        binding.buttonScan.visibility = View.VISIBLE
    }

    private fun showManualMode() {
        binding.layoutTargetInfo.visibility = View.GONE
        binding.recyclerViewNetworks.visibility = View.GONE
        binding.cardManualInput.visibility = View.VISIBLE
        binding.buttonScan.visibility = View.GONE
    }

    private fun updateTargetInfo(network: IwWifiNetwork?) {
        if (network != null) {
            binding.layoutTargetInfo.visibility = View.VISIBLE
            binding.textTargetSsid.text =
                network.ssid.ifEmpty { getString(R.string.pixiedust_hidden_network) }
            binding.textTargetBssid.text = network.bssid
        } else {
            binding.layoutTargetInfo.visibility = View.GONE
        }
    }

    private fun scanNetworks() {
        binding.progressSmall.visibility = View.VISIBLE
        binding.textStatus.text = getString(R.string.pixiedust_scanning)
        binding.buttonScan.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val networks = scanWithFallback()
                if (_binding == null) return@launch
                pixieDustAdapter.updateNetworks(networks)
                if (networks.isEmpty()) {
                    binding.textEmptyState.visibility = View.VISIBLE
                    binding.recyclerViewNetworks.visibility = View.GONE
                } else {
                    binding.textEmptyState.visibility = View.GONE
                    binding.recyclerViewNetworks.visibility = View.VISIBLE
                }
                binding.textStatus.text = getString(R.string.pixiedust_scan_complete, networks.size)
            } catch (e: SecurityException) {
                if (_binding == null) return@launch
                binding.textStatus.text = getString(R.string.location_permission_required)
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.textStatus.text = getString(R.string.iw_wifi_scan_error, e.message)
            } finally {
                if (_binding != null) {
                    binding.progressSmall.visibility = View.GONE
                    binding.buttonScan.isEnabled = true
                }
            }
        }
    }

    private suspend fun scanWithFallback(): List<IwWifiNetwork> {

        if (isChrootEnvReady) {
            try {
                chrootManager.resetMountFailedCooldown()
                if (iwWifiManager.mountChroot()) {
                    val chrootNets = iwWifiManager.scanWifiNetworks(getInterfaceFromDropdown())
                    if (chrootNets.isNotEmpty()) {
                        Log.d(TAG, "Scan source: chroot (${chrootNets.size} networks)")
                        return chrootNets
                    }
                    Log.w(TAG, "Chroot scan returned empty, falling back")
                } else {
                    Log.w(TAG, "Chroot mount failed, falling back")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Chroot scan failed, falling back", e)
            }
        }


        if (ChrootCapabilities.isRootAvailable(requireContext())) {
            try {
                if (nativeWifiHelper.ensureReady()) {
                    val iwNets = nativeWifiHelper.scanWifiNetworks(getInterfaceFromDropdown())
                    if (iwNets.isNotEmpty()) {
                        Log.d(TAG, "Scan source: in-app iw (${iwNets.size} networks)")
                        return iwNets
                    }
                    Log.w(TAG, "In-app iw scan returned empty, falling back")
                }
            } catch (e: Exception) {
                Log.w(TAG, "In-app iw scan failed, falling back", e)
            }
        }


        val systemNets = iwWifiManager.scanWifiNetworksNative()
        Log.d(TAG, "Scan source: system WifiManager (${systemNets.size} networks)")
        return systemNets
    }

    private fun loadInterfaces() {
        lifecycleScope.launch {
            try {
                val ifaces = iwWifiManager.getAvailableInterfaces()
                val names = ifaces.map { it.name }.toTypedArray()
                val prefs =
                    requireContext().getSharedPreferences(HANDSHAKE_PREFS, Context.MODE_PRIVATE)

                val savedIface = prefs.getString(KEY_CAPTURE_IFACE, names.getOrNull(0) ?: "wlan0")!!
                val argIface = pendingArgInterface
                pendingArgInterface = null

                val chosenIface = if (!argIface.isNullOrBlank()) argIface else savedIface
                currentInterface =
                    if (names.contains(chosenIface)) chosenIface else (names.firstOrNull()
                        ?: "wlan0")

                val validNames = if (names.isEmpty()) arrayOf("wlan0") else names
                setupDropdown(binding.autoCompleteBruteInterface, validNames, currentInterface)

                attachDropdownListener(prefs)
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Log.e(TAG, "Failed to load interfaces", e)
                currentInterface = "wlan0"
                setupDropdown(binding.autoCompleteBruteInterface, arrayOf("wlan0"), "wlan0")
            }
        }
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

    private fun attachDropdownListener(prefs: android.content.SharedPreferences) {
        binding.autoCompleteBruteInterface.onItemClickListener =
            AdapterView.OnItemClickListener { parent, _, position, _ ->
                val iface =
                    (parent?.getItemAtPosition(position) as? String) ?: return@OnItemClickListener
                currentInterface = iface
                prefs.edit().putString(KEY_CAPTURE_IFACE, iface).apply()
                Log.d(TAG, "Brute force interface set to: $iface")
            }
    }

    private fun proceedToAttack() {
        if (attackMode == null) {
            Toast.makeText(requireContext(), "Select attack type first", Toast.LENGTH_SHORT)
                .show(); return
        }
        if (isAttackRunning) return

        if (attackMode == AttackMode.WPS_BRUTE) {
            val bssid = getTargetBssid() ?: return
            if (!validateBssid(bssid)) return

            showConsole()
            consoleAdapter?.addLine("[*] Starting WPS Brute Force on $bssid")
            isAttackRunning = true
            goToStep(Step.ATTACK)

            val attackIface = getInterfaceFromDropdown()
            currentRunner = WpsBruteForceRunner(requireContext().applicationContext)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result =
                        currentRunner!!.runBruteForce(bssid, attackIface, onProgress = { p ->
                            val text = when {
                                p.percentComplete != null -> "${p.percentComplete}% - PIN: ${p.currentPin ?: "..."}"
                                p.currentPin != null -> "Trying PIN ${p.currentPin}"
                                else -> p.line
                            }
                            requireActivity().runOnUiThread { consoleAdapter?.addLine(text) }
                        })
                    requireActivity().runOnUiThread {
                        handleWpsResult(
                            result.wpsPin,
                            result.wpaPsk
                        )
                    }
                } catch (e: Exception) {
                    requireActivity().runOnUiThread { consoleAdapter?.addLine("[-] Error: ${e.message}") }
                } finally {
                    isAttackRunning = false
                    currentRunner = null
                }
            }
        } else {
            val ssid = getTargetSsid()
            val bssid = getTargetBssid()
            if (ssid.isNullOrEmpty() || bssid == null) return
            if (!validateBssid(bssid)) return

            if (selectedWordlistUri == null) {
                showWordlistSourceDialog()
                return
            }
            showConsole()
            consoleAdapter?.addLine("[*] Starting PSK Brute Force on $ssid ($bssid)")
            selectedWordlistLabel?.let { consoleAdapter?.addLine("[*] Wordlist: $it") }
            isAttackRunning = true
            binding.buttonRunBackground.visibility = View.GONE
            goToStep(Step.ATTACK)

            val nativeSupported = PskBruteForceEngines.isNativeSupported(requireContext())
            val engine = selectedPskEngine
                ?: if (nativeSupported) PskEngine.NATIVE else PskEngine.CHROOT

            when (engine) {
                PskEngine.NATIVE -> {
                    if (!nativeSupported) {
                        Toast.makeText(
                            requireContext(),
                            R.string.psk_engine_native_unsupported,
                            Toast.LENGTH_LONG
                        ).show()
                        abortPskAttack()
                        return
                    }
                    consoleAdapter?.addLine("[*] Engine: Native (WifiManager)")
                    startNativePskBruteForce(ssid, bssid)
                }

                PskEngine.CHROOT -> {
                    if (!isChrootEnvReady) {
                        Toast.makeText(
                            requireContext(),
                            R.string.bruteforce_chroot_required,
                            Toast.LENGTH_LONG
                        ).show()
                        abortPskAttack()
                        return
                    }
                    consoleAdapter?.addLine("[*] Engine: Chroot / root")
                    consoleAdapter?.addLine("[*] PSK Brute Force started in background")
                    ForegroundAttackService.startPskBruteForce(
                        requireContext(),
                        ssid,
                        bssid,
                        selectedWordlistUri.toString()
                    )
                }
            }
        }
    }

    private fun setupPskEngineSelector() {
        val nativeSupported = PskBruteForceEngines.isNativeSupported(requireContext())
        val chrootReady = isChrootEnvReady

        binding.radioGroupPskEngine.removeAllViews()

        val nativeRadio = RadioButton(requireContext()).apply {
            text = getString(R.string.psk_engine_native)
            id = View.generateViewId()
            isEnabled = nativeSupported
            isChecked = nativeSupported && selectedPskEngine != PskEngine.CHROOT
            setOnClickListener {
                selectedPskEngine = PskEngine.NATIVE
                updatePskEngineStatus()
            }
        }
        binding.radioGroupPskEngine.addView(nativeRadio)

        val chrootRadio = RadioButton(requireContext()).apply {
            text = getString(R.string.psk_engine_chroot)
            id = View.generateViewId()
            isEnabled = chrootReady
            isChecked = !nativeSupported || selectedPskEngine == PskEngine.CHROOT
            setOnClickListener {
                selectedPskEngine = PskEngine.CHROOT
                updatePskEngineStatus()
            }
        }
        binding.radioGroupPskEngine.addView(chrootRadio)

        if (selectedPskEngine == null) {
            selectedPskEngine = if (nativeSupported) PskEngine.NATIVE else PskEngine.CHROOT
        }
        updatePskEngineStatus()
    }

    private fun updatePskEngineStatus() {
        val nativeSupported = PskBruteForceEngines.isNativeSupported(requireContext())
        val chrootReady = isChrootEnvReady
        val messages = mutableListOf<String>()
        if (!nativeSupported) messages.add(getString(R.string.psk_engine_native_unsupported))
        if (!chrootReady) messages.add(getString(R.string.psk_engine_chroot_unsupported))
        binding.textPskEngineStatus.text = messages.joinToString("\n")
        binding.textPskEngineStatus.visibility =
            if (messages.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startNativePskBruteForce(ssid: String, bssid: String) {
        val uri = selectedWordlistUri ?: run {
            isAttackRunning = false
            return
        }
        binding.buttonRunBackground.visibility = View.VISIBLE
        val runner = NativePskBruteForceRunner(requireContext().applicationContext)
        nativeAttackJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = runner.runAttack(ssid, bssid, uri, onProgress = { p ->
                    requireActivity().runOnUiThread { consoleAdapter?.addLine(p.statusMessage) }
                })
                requireActivity().runOnUiThread { handlePskResult(result) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Native PSK brute force failed", e)
                requireActivity().runOnUiThread {
                    consoleAdapter?.addLine("[-] Error: ${e.message}")
                    finishPskAttack()
                }
            } finally {
                nativeAttackJob = null
            }
        }
    }

    private fun handlePskResult(result: PskBruteForceResult) {
        if (_binding == null) return
        if (result.success) {
            consoleAdapter?.addLine("[+] WPA PSK: ${result.foundPassword}")
            binding.iconResult.setImageResource(R.drawable.ic_check_circle)
            binding.textResultStatus.text = getString(R.string.bruteforce_success)
            binding.textResultData.text =
                "PSK: ${result.foundPassword}\n${
                    getString(
                        R.string.psk_attempts_made,
                        result.attemptsMade
                    )
                }"
        } else {
            consoleAdapter?.addLine(
                "[-] PSK not found (${getString(R.string.psk_attempts_made, result.attemptsMade)})"
            )
            binding.iconResult.setImageResource(R.drawable.ic_error)
            binding.textResultStatus.text = getString(R.string.bruteforce_failed)
            binding.textResultData.text = ""
        }
        finishPskAttack()
    }

    private fun finishPskAttack() {
        isAttackRunning = false
        if (_binding == null) return
        binding.buttonCancelAttack.visibility = View.GONE
        binding.buttonRunBackground.visibility = View.GONE
        goToStep(Step.RESULTS)
    }

    private fun abortPskAttack() {
        isAttackRunning = false
        if (_binding == null) return
        binding.buttonCancelAttack.visibility = View.GONE
        binding.buttonRunBackground.visibility = View.GONE
        goToStep(Step.TARGET)
    }

    private fun getTargetBssid(): String? {
        if (binding.tabLayout.selectedTabPosition == 0) {
            return selectedNetwork?.bssid?.uppercase()
        } else {
            val input = binding.editTextBssid.text?.toString()?.trim() ?: ""
            if (input.isEmpty()) {
                Toast.makeText(requireContext(), R.string.pixiedust_enter_bssid, Toast.LENGTH_SHORT)
                    .show()
                return null
            }
            val normalized = MacAddressUtils.formatToColonSeparated(input)
            if (normalized == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.pixiedust_invalid_bssid_format,
                    Toast.LENGTH_SHORT
                ).show()
                return null
            }
            return normalized.uppercase()
        }
    }

    private fun getTargetSsid(): String? {
        if (binding.tabLayout.selectedTabPosition == 0) {
            val ssid = selectedNetwork?.ssid ?: ""
            if (ssid.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "No SSID for selected network, enter manually",
                    Toast.LENGTH_SHORT
                ).show()
                return null
            }
            return ssid
        }
        val ssid = binding.editTextSsid.text?.toString()?.trim() ?: ""
        if (ssid.isEmpty()) {
            Toast.makeText(requireContext(), R.string.psk_ssid_hint, Toast.LENGTH_SHORT).show()
            return null
        }
        return ssid
    }

    private fun validateBssid(bssid: String): Boolean {
        if (!BSSID_REGEX.matches(bssid)) {
            Toast.makeText(requireContext(), R.string.invalid_bssid, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun showWordlistSourceDialog() {
        val picker = PskWordlistSourcePicker()
        picker.onWordlistSelected = { uri, label ->
            selectedWordlistUri = uri
            selectedWordlistLabel = label
            proceedToAttack()
        }
        picker.show(parentFragmentManager, "PskWordlistSourcePicker")
    }

    private fun showConsole() {
        consoleAdapter = ConsoleAdapter(autoScroll = true)
        binding.recyclerViewConsole.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = consoleAdapter
        }
        consoleAdapter?.attachToRecyclerView(binding.recyclerViewConsole)
    }

    private fun toggleConsole() {
        val visible = binding.recyclerViewConsole.visibility == View.VISIBLE
        binding.recyclerViewConsole.visibility = if (visible) View.GONE else View.VISIBLE
        binding.iconToggleConsole.setImageResource(if (visible) R.drawable.ic_expand_more else R.drawable.ic_expand_less)
    }

    private fun handleWpsResult(pin: String?, psk: String?) {
        isAttackRunning = false
        binding.buttonCancelAttack.visibility = View.GONE
        binding.buttonRunBackground.visibility = View.GONE

        if (pin != null) {
            consoleAdapter?.addLine("[+] WPS PIN: $pin")
            if (psk != null) consoleAdapter?.addLine("[+] WPA PSK: $psk")
            binding.iconResult.setImageResource(R.drawable.ic_check_circle)
            binding.textResultStatus.text = getString(R.string.bruteforce_success)
            binding.textResultData.text = "PIN: $pin${if (psk != null) "\nPSK: $psk" else ""}"
            goToStep(Step.RESULTS)
        } else {
            consoleAdapter?.addLine("[-] WPS PIN not found")
            binding.iconResult.setImageResource(R.drawable.ic_error)
            binding.textResultStatus.text = getString(R.string.bruteforce_failed)
            binding.textResultData.text = ""
            goToStep(Step.RESULTS)
        }
    }

    private fun cancelAttack() {
        if (!isAttackRunning) return
        isAttackRunning = false
        binding.buttonCancelAttack.isEnabled = false
        consoleAdapter?.addLine("[-] Attack cancelled")
        nativeAttackJob?.cancel()
        nativeAttackJob = null
        lifecycleScope.launch {
            try {
                withTimeout(30_000L) { currentRunner?.cancel() }
            } catch (_: Exception) {
            }
            if (_binding != null) {
                binding.buttonCancelAttack.visibility = View.GONE
                binding.buttonRunBackground.visibility = View.GONE
            }
        }
    }

    private fun runInBackground() {
        if (!isAttackRunning) return
        when (attackMode) {
            AttackMode.WPS_BRUTE -> {
                val bssid = getTargetBssid() ?: return
                val attackIface = getInterfaceFromDropdown()
                ForegroundAttackService.startWpsBruteForce(requireContext(), bssid, attackIface)
            }

            AttackMode.PSK_BRUTE -> {
                val ssid = getTargetSsid() ?: return
                val bssid = getTargetBssid() ?: return
                val uri = selectedWordlistUri ?: return
                val engine = selectedPskEngine
                    ?: if (PskBruteForceEngines.isNativeSupported(requireContext())) {
                        PskEngine.NATIVE
                    } else {
                        PskEngine.CHROOT
                    }
                if (engine == PskEngine.NATIVE) {
                    ForegroundAttackService.startNativePskBruteForce(
                        requireContext(), ssid, bssid, uri.toString()
                    )
                } else {
                    ForegroundAttackService.startPskBruteForce(
                        requireContext(), ssid, bssid, uri.toString()
                    )
                }
            }

            null -> return
        }
        isAttackRunning = false
        nativeAttackJob?.cancel()
        nativeAttackJob = null
        Toast.makeText(requireContext(), R.string.foreground_attack_running, Toast.LENGTH_SHORT)
            .show()
        goToStep(Step.SELECT_TYPE)
    }

    private fun showBackgroundResult(text: String) {
        if (isAdded) {
            Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(attackReceiver)
        nativeAttackJob?.cancel()
        nativeAttackJob = null
        _binding = null
    }

    companion object {
        private val BSSID_REGEX = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        private const val HANDSHAKE_PREFS = "handshake_capture"
        private const val KEY_CAPTURE_IFACE = "capture_interface"
    }
}
