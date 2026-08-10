package com.lsd.wififrankenstein.ui.welcome

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.RootSelection
import com.lsd.wififrankenstein.WelcomeActivity
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.databinding.FragmentChrootInstallBinding
import com.lsd.wififrankenstein.service.ChrootInstallService
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.RootlessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChrootInstallFragment : Fragment() {

    private var _binding: FragmentChrootInstallBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WelcomeViewModel by activityViewModels()
    private lateinit var chrootManager: ChrootManager
    private lateinit var rootlessManager: RootlessManager
    private var started: Boolean = false
    private var pendingDownload = false
    private val diagnosticLog = mutableListOf<Triple<String, String, String>>()
    private val diagnosticFullLog = mutableListOf<Pair<String, String>>()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ChrootInstallService.BROADCAST_CHROOT_PROGRESS -> {
                    val progress = intent.getIntExtra(ChrootInstallService.EXTRA_PROGRESS, 0)
                    val fileSize = intent.getLongExtra(ChrootInstallService.EXTRA_FILE_SIZE, 0L)
                    val clampedProgress = progress.coerceAtLeast(15)
                    binding.progressBar.progress = clampedProgress
                    binding.textViewProgress.visibility = VISIBLE
                    if (clampedProgress < 45 && fileSize > 0) {
                        val mbDownloaded =
                            ((clampedProgress - 15) * fileSize / 30L / (1024 * 1024)).toInt()
                        val totalMb = (fileSize / (1024 * 1024)).toInt()
                        binding.textViewProgress.text =
                            getString(R.string.chroot_progress_mb, mbDownloaded, totalMb)
                    } else {
                        binding.textViewProgress.text =
                            getString(R.string.progress_percent, clampedProgress)
                    }
                }

                ChrootInstallService.BROADCAST_CHROOT_STATUS -> {
                    val status = intent.getStringExtra(ChrootInstallService.EXTRA_STATUS) ?: ""
                    binding.textViewStatus.text = status
                }

                ChrootInstallService.BROADCAST_CHROOT_DIAGNOSTIC -> {
                    val name = intent.getStringExtra(ChrootInstallService.EXTRA_DIAG_NAME) ?: ""
                    val icon = intent.getStringExtra(ChrootInstallService.EXTRA_DIAG_ICON) ?: ""
                    val result = intent.getStringExtra(ChrootInstallService.EXTRA_DIAG_RESULT) ?: ""
                    val fullOutput =
                        intent.getStringExtra(ChrootInstallService.EXTRA_DIAG_OUTPUT) ?: ""
                    diagnosticLog.add(Triple(name, icon, result))
                    diagnosticFullLog.add(Pair(name, fullOutput))
                    addDiagnosticCard(name, icon, result)
                    binding.buttonCopyLog.visibility = VISIBLE
                }

                ChrootInstallService.BROADCAST_CHROOT_COMPLETED -> {
                    handleInstallationResult(true)
                }

                ChrootInstallService.BROADCAST_CHROOT_FAILED -> {
                    handleInstallationResult(false)
                }

                ChrootInstallService.BROADCAST_CHROOT_CANCELLED -> {
                    handleInstallationCancelled()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChrootInstallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("ChrootInstall", "=== onViewCreated START ===")

        (activity as? WelcomeActivity)?.setBottomHint(null)
        chrootManager = ChrootManager(requireContext())
        rootlessManager = RootlessManager(requireContext())

        setupViews()
        setupArchitecture()

        viewModel.rootEnabled.observe(this) { enabled ->
            Log.d("ChrootInstall", "observe() fired with enabled=$enabled, started=$started")
            handleRootEnabledChange(enabled)
        }

        registerBroadcastReceiver()

        Log.d("ChrootInstall", "=== onViewCreatAed END ===")
    }

    override fun onResume() {
        super.onResume()
        Log.d("ChrootInstall", "=== onResume: isResumed=true, started=$started ===")
        if (!started) {
            viewModel.rootEnabled.value?.let { enabled ->
                Log.d("ChrootInstall", "onResume: processing latest rootEnabled=$enabled")
                startBasedOnRootStatus(enabled)
            }
        }
    }

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(ChrootInstallService.BROADCAST_CHROOT_PROGRESS)
            addAction(ChrootInstallService.BROADCAST_CHROOT_STATUS)
            addAction(ChrootInstallService.BROADCAST_CHROOT_COMPLETED)
            addAction(ChrootInstallService.BROADCAST_CHROOT_FAILED)
            addAction(ChrootInstallService.BROADCAST_CHROOT_CANCELLED)
            addAction(ChrootInstallService.BROADCAST_CHROOT_DIAGNOSTIC)
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(broadcastReceiver, filter)
    }

    private fun unregisterBroadcastReceiver() {
        try {
            LocalBroadcastManager.getInstance(requireContext())
                .unregisterReceiver(broadcastReceiver)
        } catch (e: Exception) {
            Log.w("ChrootInstall", "Failed to unregister receiver", e)
        }
    }

    private fun handleRootEnabledChange(enabled: Boolean) {
        if (!isResumed) {
            Log.d(
                "ChrootInstall",
                "Fragment not resumed (preload), deferring to onResume (enabled=$enabled)"
            )
            return
        }
        startBasedOnRootStatus(enabled)
    }

    private fun startBasedOnRootStatus(enabled: Boolean) {
        if (started) {
            Log.d("ChrootInstall", "Already started, ignoring (enabled=$enabled)")
            return
        }
        started = true
        Log.d("ChrootInstall", "startBasedOnRootStatus called with enabled=$enabled")

        if (!enabled) {
            Log.d("ChrootInstall", "Root disabled, checking rootless compatibility...")
            if (rootlessManager.isSupportedArchitecture()) {
                val config = rootlessManager.getRuntimeConfig()
                if (config != null && rootlessManager.isSetupCompleted()) {
                    Log.d("ChrootInstall", "Rootless already set up, proceeding")
                    showNextButton()
                    (activity as? WelcomeActivity)?.navigateToNextFragment()
                    return
                }
                showRootlessSetupUI()
            } else {
                Log.d("ChrootInstall", "Rootless not supported on this architecture")
                showNextButton()
                (activity as? WelcomeActivity)?.navigateToNextFragment()
            }
            return
        }

        val chrootInstalled = chrootManager.isChrootInstalled()
        Log.d("ChrootInstall", "Chroot installed=$chrootInstalled")
        if (chrootInstalled) {
            Log.d("ChrootInstall", "Chroot already installed, showing choice dialog")
            showExistingChrootDialog()
            return
        }

        val chrootType = chrootManager.getChrootType()
        if (chrootType is com.lsd.wififrankenstein.util.ChrootType.RootWithoutChroot) {
            Log.d("ChrootInstall", "Root available but chroot blocked, offering rootless")
            showRootlessSetupUI()
            return
        }

        Log.d("ChrootInstall", "Showing download state")
        showDownloadUI()
    }

    private fun showRootlessSetupUI() {
        binding.textViewFileSize.visibility = VISIBLE
        binding.textViewFileSize.text = getString(R.string.rootless_approx_size, 137)
        binding.textViewArchitecture.visibility = VISIBLE
        setupArchitecture()
        binding.linearLayoutDownloadButtons.visibility = VISIBLE
        binding.buttonDownload.text = getString(R.string.install_rootless)
        binding.linearLayoutActionButtons.visibility = VISIBLE
        binding.buttonCancel.visibility = GONE
        binding.buttonRetry.visibility = GONE
        setSkipButtonVisibility(VISIBLE)
        binding.buttonCopyLog.visibility = GONE
        binding.progressBar.visibility = GONE
        binding.textViewStatus.text = getString(R.string.rootless_download_info)
        hideNextButton()
    }

    private fun showExistingChrootDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.chroot_existing_dialog_title)
            .setMessage(R.string.chroot_existing_dialog_message)
            .setPositiveButton(R.string.chroot_use_existing) { _, _ ->
                Log.d("ChrootInstall", "Using existing chroot, navigating to next")
                setupArchitecture()
                binding.textViewArchitecture.visibility = VISIBLE
                binding.textViewFileSize.visibility = GONE
                binding.linearLayoutDownloadButtons.visibility = GONE
                binding.linearLayoutActionButtons.visibility = GONE
                binding.progressBar.visibility = GONE
                binding.textViewStatus.text = getString(R.string.chroot_already_installed)
                showNextButton()
                (activity as? WelcomeActivity)?.navigateToNextFragment()
            }
            .setNegativeButton(R.string.chroot_clean_install) { _, _ ->
                Log.d("ChrootInstall", "Clean install chosen, removing existing chroot")
                started = false
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    chrootManager.uninstall()
                    withContext(Dispatchers.Main) {
                        showDownloadUI()
                    }
                }
            }
            .show()
    }

    private fun showDownloadUI() {
        binding.textViewFileSize.visibility = VISIBLE
        binding.textViewFileSize.text = getString(R.string.chroot_approx_size, 114)
        binding.textViewArchitecture.visibility = GONE
        binding.linearLayoutDownloadButtons.visibility = VISIBLE
        binding.linearLayoutActionButtons.visibility = VISIBLE
        binding.buttonCancel.visibility = GONE
        binding.buttonRetry.visibility = GONE
        setSkipButtonVisibility(VISIBLE)
        binding.buttonCopyLog.visibility = GONE
        binding.progressBar.visibility = GONE
        binding.textViewStatus.text = getString(R.string.chroot_download_info)
        hideNextButton()
    }

    private fun hideDownloadUI() {
        binding.linearLayoutDownloadButtons.visibility = GONE
        binding.linearLayoutActionButtons.visibility = GONE
        binding.progressBar.visibility = GONE
        binding.textViewFileSize.visibility = GONE
    }

    private fun showNextButton() {
        (activity as? WelcomeActivity)?.setBottomBarVisible(true)
        (activity as? WelcomeActivity)?.updateNavigationButtons(
            showPrev = true,
            showNext = true,
            nextText = getString(R.string.next)
        )
    }

    private fun hideNextButton() {
        (activity as? WelcomeActivity)?.setBottomBarVisible(false)
        (activity as? WelcomeActivity)?.updateNavigationButtons(
            showPrev = false,
            showNext = false,
            nextText = getString(R.string.next)
        )
    }

    private fun setSkipButtonVisibility(visibility: Int) {
        binding.buttonSkip.visibility = visibility
        binding.dividerSkip.visibility = visibility
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun addDiagnosticCard(name: String, icon: String, result: String) {
        val isSummary = name == "Problems"
        val iconColor = when {
            icon.contains("✗") -> 0xFFC62828.toInt()
            icon.contains("!") -> 0xFFE65100.toInt()
            icon.contains("?") -> 0xFF757575.toInt()
            else -> 0xFF2E7D32.toInt()
        }
        val cardBg = when {
            icon.contains("✗") -> 0x1AFF5252.toInt()
            icon.contains("!") -> 0x1AFF9800.toInt()
            icon.contains("?") -> 0x1A9E9E9E.toInt()
            else -> 0x1A69F0AE.toInt()
        }

        if (isSummary) {
            val separator = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { setMargins(0, dp(8), 0, dp(4)) }
                setBackgroundColor(0x1AFF5252.toInt())
            }
            binding.layoutDiagnostics.addView(separator)
        }

        val card = MaterialCardView(requireContext()).apply {
            val marginBottom = if (isSummary) dp(8) else dp(4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(0), dp(0), dp(0), marginBottom) }
            cardElevation = 0f
            radius = dp(8).toFloat()
            val padV = if (isSummary) dp(12) else dp(8)
            setContentPadding(dp(12), padV, dp(12), padV)
            setCardBackgroundColor(cardBg)
            if (isSummary) {
                strokeColor = 0xFFFF5252.toInt()
                strokeWidth = dp(2)
            }
        }

        val container = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        val topRow = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(requireContext()).apply {
            text = icon.trim()
            textSize = if (isSummary) 16f else 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(iconColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        topRow.addView(TextView(requireContext()).apply {
            text = name
            textSize = if (isSummary) 15f else 13f
            typeface = if (isSummary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(dp(8), 0, dp(8), 0) }
        })
        container.addView(topRow)

        container.addView(TextView(requireContext()).apply {
            text = result
            textSize = if (isSummary) 12f else 11f
            alpha = if (isSummary) 1f else 0.7f
            maxLines = if (isSummary) 20 else 2
            ellipsize = if (isSummary) null else android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(20), dp(4), 0, 0) }
        })
        card.addView(container)
        binding.layoutDiagnostics.addView(card)
        binding.layoutDiagnostics.visibility = VISIBLE
        binding.scrollViewRoot.post {
            binding.scrollViewRoot.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun setupViews() {
        binding.buttonDownload.setOnClickListener {
            Log.d("ChrootInstall", ">>> Download button clicked <<<")
            val hasRoot = viewModel.rootEnabled.value == true
            val selection = viewModel.rootSelection.value

            val shouldProbeRoot = hasRoot || selection == RootSelection.DONT_KNOW
            if (shouldProbeRoot) {
                val type = chrootManager.getChrootType()
                val chrootAvailable = type is com.lsd.wififrankenstein.util.ChrootType.Root ||
                        type is com.lsd.wififrankenstein.util.ChrootType.RootMissing
                if (chrootAvailable) {
                    startDownload()
                    return@setOnClickListener
                }
                if (hasRoot) {
                    binding.textViewStatus.text = getString(R.string.root_access_not_available)
                    Log.d(
                        "ChrootInstall",
                        "User said root available but probe failed, falling back to rootless"
                    )
                }
            }
            startRootlessDownload()
        }

        binding.buttonSkip.setOnClickListener {
            Log.d("ChrootInstall", ">>> Skip button clicked <<<")
            started = true
            hideDownloadUI()
            showNextButton()
            (activity as? WelcomeActivity)?.navigateToNextFragment()
        }

        binding.buttonCancel.setOnClickListener {
            Log.d("ChrootInstall", ">>> Cancel button clicked <<<")
            cancelInstallation()
        }
    }

    private fun isMobileNetwork(): Boolean {
        val connectivityManager =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        } else {
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager.activeNetworkInfo ?: return false
            return activeNetworkInfo.type != ConnectivityManager.TYPE_WIFI
        }
    }

    private fun startDownload() {
        if (isMobileNetwork()) {
            pendingDownload = true
            showMobileWarningDialog { executeDownload() }
            return
        }
        executeDownload()
    }

    private fun showMobileWarningDialog(onContinue: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.warning)
            .setMessage(R.string.mobile_download_warning)
            .setPositiveButton(R.string.mobile_download_continue) { _, _ ->
                onContinue()
            }
            .setNegativeButton(R.string.mobile_download_cancel) { _, _ ->
                pendingDownload = false
                Log.d("ChrootInstall", "User cancelled mobile download")
            }
            .show()
    }

    private fun startRootlessDownload() {
        if (isMobileNetwork()) {
            pendingDownload = true
            showMobileWarningDialog { executeRootlessDownload() }
            return
        }
        executeRootlessDownload()
    }

    private fun executeRootlessDownload() {
        diagnosticLog.clear()
        diagnosticFullLog.clear()
        binding.layoutDiagnostics.removeAllViews()
        binding.layoutDiagnostics.visibility = GONE
        binding.buttonCopyLog.visibility = GONE
        binding.buttonCancel.visibility = VISIBLE
        binding.buttonCancel.isEnabled = true
        binding.linearLayoutDownloadButtons.visibility = GONE
        binding.linearLayoutActionButtons.visibility = VISIBLE
        binding.linearLayoutActionButtons.alpha = 0f
        binding.linearLayoutActionButtons.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
        binding.progressBar.visibility = VISIBLE
        binding.progressBar.progress = 5
        binding.textViewProgress.visibility = VISIBLE
        binding.textViewArchitecture.visibility = VISIBLE
        setupArchitecture()

        lifecycleScope.launch(Dispatchers.IO) {
            Log.d("ChrootInstall", "=== Rootless setup START ===")
            updateStatus("Setting up rootless environment...")

            updateStatus("Fetching rootfs download URL...")
            val chrootInfo = chrootManager.getChrootInfo()
            val archive = chrootInfo?.let {
                if (chrootManager.isAarch64()) it.aarch64 else it.armhf
            }
            if (archive == null) {
                withContext(Dispatchers.Main) {
                    binding.textViewStatus.text = "Failed to get rootfs URL"
                    showRetryButtons()
                }
                return@launch
            }

            val success = rootlessManager.setupRootfs(
                onProgress = { progress ->
                    updateProgress(progress)
                },
                onStatusUpdate = { status ->
                    updateStatus(status)
                },
                downloadUrl = archive.download_url,
                onDiagnosticUpdate = { name, icon, result ->
                    launchOnMain {
                        addDiagnosticCard(name, icon, result)
                    }
                }
            )

            withContext(Dispatchers.Main) {
                if (success) {
                    binding.progressBar.progress = 100
                    binding.textViewStatus.text = getString(R.string.chroot_installation_completed)
                    binding.buttonCancel.visibility = GONE
                    hideDownloadUI()
                    showNextButton()
                    (activity as? WelcomeActivity)?.navigateToNextFragment()
                } else {
                    showRetryButtons()
                }
            }
        }
    }

    private fun updateProgress(progress: Int) {
        launchOnMain {
            binding.progressBar.progress = progress.coerceAtMost(100)
            binding.textViewProgress.text = getString(R.string.progress_percent, progress)
        }
    }

    private fun updateStatus(status: String) {
        launchOnMain {
            binding.textViewStatus.text = status
        }
    }

    private fun launchOnMain(action: () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            action()
        }
    }

    private fun showRetryButtons() {
        binding.buttonCancel.visibility = GONE
        binding.linearLayoutActionButtons.visibility = VISIBLE
        binding.buttonRetry.visibility = VISIBLE
        setSkipButtonVisibility(VISIBLE)
        binding.buttonCopyLog.visibility = VISIBLE
        hideNextButton()

        binding.buttonRetry.setOnClickListener {
            binding.progressBar.progress = 0
            binding.textViewProgress.text = getString(R.string.progress_percent, 0)
            binding.buttonRetry.visibility = GONE
            binding.buttonCancel.visibility = VISIBLE
            executeRootlessDownload()
        }

        binding.buttonSkip.setOnClickListener {
            setupArchitecture()
            binding.textViewArchitecture.visibility = VISIBLE
            binding.textViewFileSize.visibility = GONE
            binding.linearLayoutDownloadButtons.visibility = VISIBLE
            binding.linearLayoutActionButtons.visibility = GONE
            binding.progressBar.progress = 0
            binding.textViewProgress.text = getString(R.string.progress_percent, 0)
            binding.textViewStatus.text = "Rootless setup skipped"
            showNextButton()
            (activity as? WelcomeActivity)?.navigateToNextFragment()
        }

        binding.buttonCopyLog.setOnClickListener {
            val log = buildDiagnosticLog()
            val clipboard =
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("rootless_diagnostic", log))
            Toast.makeText(requireContext(), R.string.log_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun executeDownload() {
        diagnosticLog.clear()
        diagnosticFullLog.clear()
        binding.layoutDiagnostics.removeAllViews()
        binding.layoutDiagnostics.visibility = GONE
        binding.buttonCopyLog.visibility = GONE
        binding.buttonCancel.visibility = VISIBLE
        binding.buttonCancel.isEnabled = true
        binding.linearLayoutDownloadButtons.visibility = GONE
        binding.linearLayoutActionButtons.visibility = VISIBLE
        binding.linearLayoutActionButtons.alpha = 0f
        binding.linearLayoutActionButtons.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
        binding.progressBar.visibility = VISIBLE
        binding.progressBar.progress = 15
        binding.textViewProgress.visibility = VISIBLE
        binding.textViewArchitecture.visibility = VISIBLE
        setupArchitecture()

        Log.d("ChrootInstall", "Starting ChrootInstallService")
        ChrootInstallService.startInstallation(requireContext())
    }

    private fun setupArchitecture() {
        val arch = chrootManager.getArchitecture()
        binding.textViewArchitecture.text =
            getString(R.string.installing_for_architecture, arch.label)
        Log.d("ChrootInstall", "Architecture: $arch")
        if (!arch.isArm) {
            binding.textViewArchWarning.text = getString(R.string.chroot_arch_warning, arch.label)
            binding.textViewArchWarning.visibility = VISIBLE
        } else {
            binding.textViewArchWarning.visibility = GONE
        }
    }

    private fun cancelInstallation() {
        Log.d("ChrootInstall", ">>> cancelInstallation <<<")
        ChrootInstallService.cancelInstallation(requireContext())
        binding.buttonCancel.isEnabled = false
    }

    private fun handleInstallationCancelled() {
        Log.d("ChrootInstall", ">>> handleInstallationCancelled <<<")
        binding.buttonCancel.isEnabled = false
        binding.buttonCancel.visibility = GONE
        binding.linearLayoutActionButtons.visibility = GONE
        binding.linearLayoutDownloadButtons.visibility = VISIBLE
        binding.textViewStatus.text = getString(R.string.installation_cancelled)
        binding.progressBar.visibility = GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("started", started)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState != null) {
            started = savedInstanceState.getBoolean("started", false)
        }
    }

    private fun buildDiagnosticLog(): String = buildString {
        appendLine("WIFI-Frankenstein Chroot Diagnostics")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE}, SDK: ${Build.VERSION.SDK_INT}")
        appendLine()
        diagnosticFullLog.forEach { (name, output) ->
            appendLine("=== $name ===")
            output.lineSequence().forEach { line ->
                if (line.isNotBlank()) appendLine(line)
            }
            appendLine()
        }
    }

    private fun handleInstallationResult(success: Boolean) {
        Log.d("ChrootInstall", ">>> handleInstallationResult success=$success <<<")
        binding.buttonCancel.isEnabled = false
        binding.buttonCancel.visibility = GONE

        if (success) {
            binding.textViewStatus.text = getString(R.string.chroot_installation_completed)
            Log.d("ChrootInstall", "Installation successful, showing Next")
            hideDownloadUI()
            showNextButton()
            (activity as? WelcomeActivity)?.navigateToNextFragment()
        } else {
            binding.linearLayoutDownloadButtons.visibility = GONE
            binding.linearLayoutActionButtons.visibility = VISIBLE
            binding.buttonRetry.visibility = VISIBLE
            setSkipButtonVisibility(VISIBLE)
            binding.buttonCopyLog.visibility = VISIBLE
            Log.d("ChrootInstall", "Installation failed, showing Retry and Skip")
            hideNextButton()

            binding.buttonRetry.setOnClickListener {
                Log.d("ChrootInstall", ">>> Retry button clicked <<<")
                binding.progressBar.progress = 0
                binding.textViewProgress.text = getString(R.string.progress_percent, 0)
                binding.buttonRetry.visibility = GONE
                binding.buttonCancel.visibility = VISIBLE
                executeDownload()
            }

            setSkipButtonVisibility(VISIBLE)
            binding.buttonSkip.setOnClickListener {
                Log.d("ChrootInstall", ">>> Skip button clicked, proceeding without chroot <<<")
                setupArchitecture()
                binding.textViewArchitecture.visibility = VISIBLE
                binding.textViewFileSize.visibility = GONE
                binding.linearLayoutDownloadButtons.visibility = VISIBLE
                binding.linearLayoutActionButtons.visibility = GONE
                binding.progressBar.visibility = GONE
                binding.textViewStatus.text = getString(R.string.chroot_skipped_no_root)
                showNextButton()
                (activity as? WelcomeActivity)?.navigateToNextFragment()
            }

            binding.buttonCopyLog.setOnClickListener {
                val log = buildDiagnosticLog()
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("chroot_diagnostic", log))
                Toast.makeText(requireContext(), R.string.log_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d("ChrootInstall", "=== onDestroyView ===")
        unregisterBroadcastReceiver()
        _binding = null
    }

    companion object {
        fun newInstance() = ChrootInstallFragment()
    }
}
