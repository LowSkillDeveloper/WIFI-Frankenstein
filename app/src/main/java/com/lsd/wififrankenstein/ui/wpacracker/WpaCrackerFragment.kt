package com.lsd.wififrankenstein.ui.wpacracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.BottomSheetSourcePickerBinding
import com.lsd.wififrankenstein.databinding.BottomSheetStoragePickerBinding
import com.lsd.wififrankenstein.databinding.FragmentWpaCrackerBinding
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeItem
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeStorageManager
import com.lsd.wififrankenstein.ui.pixiedust.ConsoleAdapter
import com.lsd.wififrankenstein.util.BenchmarkProgress
import com.lsd.wififrankenstein.util.ChrootCapabilities
import com.lsd.wififrankenstein.util.HandshakeHash
import com.lsd.wififrankenstein.util.HandshakeParser
import com.lsd.wififrankenstein.util.HandshakeType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.OfflineProgress
import com.lsd.wififrankenstein.util.OfflineResult
import com.lsd.wififrankenstein.util.WpaBenchmark
import com.lsd.wififrankenstein.util.WpaCracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class SourceOption(val iconRes: Int, val title: String)

class WpaCrackerFragment : Fragment() {

    private var _binding: FragmentWpaCrackerBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: WpaCrackerViewModel
    private var consoleAdapter: ConsoleAdapter? = null

    private val handshakeFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadHandshakeFile(uri)
        }
    }

    private val wordlistFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.setWordlistFile(uri)
            binding.textWordlistInfo.text = uri.lastPathSegment ?: "wordlist.txt"
            updateStartButton()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWpaCrackerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[WpaCrackerViewModel::class.java]

        setupEngineSelector()
        setupClickListeners()
        setupObservers()

        val hash22000 = arguments?.getString("hash22000") ?: ""
        val wordlistType = arguments?.getInt("wordlistType", -1) ?: -1
        val wordlistValue = arguments?.getString("wordlistValue") ?: ""

        if (hash22000.isNotBlank()) {
            viewModel.setHandshakeLine(hash22000)
            if (wordlistType >= 0) {
                handleAutoWordlist(wordlistType, wordlistValue)
            }
        }
    }

    private fun handleAutoWordlist(type: Int, value: String) {
        when (type) {
            0 -> {
                viewModel.setWordlistFile(Uri.parse(value))
                autoStartWhenReady()
            }

            1 -> viewModel.trySinglePassword(value)
            2 -> {
                val lines = File(value).readLines()
                viewModel.setWordlistFromPaste(lines)
                autoStartWhenReady()
            }

            3 -> {
                viewModel.useWpaSecDict()
                autoStartWhenReady()
            }
        }
    }

    private fun autoStartWhenReady() {
        if (viewModel.isPreparingWordlist.value != true) {
            viewModel.startCracking()
            return
        }
        val observer = object : androidx.lifecycle.Observer<Boolean> {
            override fun onChanged(preparing: Boolean) {
                if (!preparing) {
                    viewModel.isPreparingWordlist.removeObserver(this)
                    viewModel.startCracking()
                }
            }
        }
        viewModel.isPreparingWordlist.observe(viewLifecycleOwner, observer)
    }

    private fun setupEngineSelector() {
        viewModel.availableEngines.observe(viewLifecycleOwner) { engines ->
            binding.radioGroupEngines.removeAllViews()
            for (engine in engines) {
                val radio = RadioButton(requireContext()).apply {
                    text = when (engine) {
                        CrackEngine.NATIVE -> "Native — PBKDF2 in-app"
                        CrackEngine.CHROOT_AIRCRACK -> "Chroot aircrack-ng"
                    }
                    id = View.generateViewId()
                    isChecked = engine == viewModel.selectedEngine.value
                    setOnClickListener {
                        if (engine == CrackEngine.CHROOT_AIRCRACK && !ChrootCapabilities.isAvailable(
                                requireContext()
                            )
                        ) {
                            Snackbar.make(
                                binding.root,
                                "Chroot is not installed. Install it in Settings → Chroot or Welcome wizard.",
                                Snackbar.LENGTH_LONG
                            ).show()
                            return@setOnClickListener
                        }
                        viewModel.setEngine(engine)
                    }
                }
                binding.radioGroupEngines.addView(radio)
            }
        }

        viewModel.selectedEngine.observe(viewLifecycleOwner) { engine ->
            for (i in 0 until binding.radioGroupEngines.childCount) {
                val radio = binding.radioGroupEngines.getChildAt(i) as? RadioButton ?: continue
                val tag = when (engine) {
                    CrackEngine.NATIVE -> "Native"
                    CrackEngine.CHROOT_AIRCRACK -> "Chroot"
                }
                radio.isChecked = radio.text.toString().contains(tag, ignoreCase = true)
            }
            updateUIBasedOnEngine(engine)
        }

        viewModel.chrootStatus.observe(viewLifecycleOwner) { status ->
            binding.textChrootStatus.isVisible = status.isNotEmpty()
            binding.textChrootStatus.text = status
        }
    }

    private fun updateUIBasedOnEngine(engine: CrackEngine) {
        when (engine) {
            CrackEngine.NATIVE -> {
                binding.cardNativeProgress.isVisible = false
                binding.cardChrootConsole.isVisible = false
                binding.cardChrootProgress.isVisible = false
            }

            CrackEngine.CHROOT_AIRCRACK -> {
                binding.cardNativeProgress.isVisible = false
            }
        }
    }

    private fun setupClickListeners() {
        binding.cardHandshakeSource.setOnClickListener {
            showHandshakeSourceDialog()
        }

        binding.cardWordlist.setOnClickListener {
            showWordlistSourceDialog()
        }

        binding.buttonStartCrack.setOnClickListener {
            when (val state = viewModel.state.value) {
                is WpaCrackerState.Cracking -> {}
                is WpaCrackerState.Paused -> viewModel.resumeCracking()
                else -> {
                    viewModel.startCracking()
                }
            }
        }

        binding.buttonPauseResume.setOnClickListener {
            if (viewModel.isPaused.value == true) {
                viewModel.resumeCracking()
            } else {
                viewModel.pauseCracking()
            }
        }

        binding.buttonStopCrack.setOnClickListener {
            viewModel.cancel()
            binding.buttonPauseResume.isVisible = false
            binding.buttonStopCrack.isVisible = false
        }

        binding.buttonCancel.setOnClickListener {
            viewModel.cancel()
        }

        binding.buttonCopyPassword.setOnClickListener {
            val password = binding.textResultPassword.text?.toString()
            if (!password.isNullOrEmpty()) {
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("WPA Password", password))
                Toast.makeText(requireContext(), "Password copied", Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonRunBenchmark.setOnClickListener {
            runBenchmark()
        }
    }

    private fun showSourcePickerBottomSheet(
        title: String,
        options: List<SourceOption>,
        onSelected: (Int) -> Unit
    ) {
        val dialog = BottomSheetDialog(requireContext())
        val binding = BottomSheetSourcePickerBinding.inflate(LayoutInflater.from(requireContext()))
        dialog.setContentView(binding.root)

        binding.pickerTitle.text = title
        binding.btnPickerClose.setOnClickListener { dialog.dismiss() }

        val density = resources.displayMetrics.density
        val dp4 = (4 * density).toInt()
        val dp16 = (16 * density).toInt()
        val dp14 = (14 * density).toInt()
        val dp24 = (24 * density).toInt()

        binding.optionsContainer.removeAllViews()
        for ((index, option) in options.withIndex()) {
            val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { if (index > 0) it.topMargin = dp4 }
                radius =
                    resources.getDimension(com.google.android.material.R.dimen.mtrl_card_corner_radius)
                cardElevation = 0f
                setStrokeColor(
                    android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(
                            requireContext(),
                            R.color.divider_color
                        )
                    )
                )
                strokeWidth = resources.getDimensionPixelSize(R.dimen.stroke_default)
                isClickable = true
                isFocusable = true
                val attr = android.util.TypedValue()
                requireContext().theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, attr, true
                )
                if (attr.resourceId != 0) {
                    foreground = androidx.core.content.ContextCompat.getDrawable(
                        requireContext(), attr.resourceId
                    )
                }
                setOnClickListener {
                    dialog.dismiss()
                    onSelected(index)
                }
            }

            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp16, dp14, dp16, dp14)
            }

            val icon = android.widget.ImageView(requireContext()).apply {
                setImageResource(option.iconRes)
                layoutParams = android.widget.LinearLayout.LayoutParams(dp24, dp24)
                val tintValue = android.util.TypedValue()
                requireContext().theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnSurfaceVariant, tintValue, true
                )
                imageTintList = android.content.res.ColorStateList.valueOf(tintValue.data)
            }

            val text = android.widget.TextView(requireContext()).apply {
                text = option.title
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.marginStart = dp16 }
            }

            row.addView(icon)
            row.addView(text)
            card.addView(row)
            binding.optionsContainer.addView(card)
        }

        dialog.show()
    }

    private fun showHandshakeSourceDialog() {
        val options = listOf(
            SourceOption(R.drawable.ic_folder_open, "From File (.cap, .22000, etc.)"),
            SourceOption(R.drawable.ic_file_download, "From URL (direct / MEGA)"),
            SourceOption(R.drawable.ic_content_copy, "Paste Hash(es)"),
            SourceOption(R.drawable.ic_database, "From Handshake Storage")
        )
        showSourcePickerBottomSheet("Select handshake source", options) { which ->
            when (which) {
                0 -> handshakeFilePicker.launch(arrayOf("*/*"))
                1 -> showHandshakeUrlDialog()
                2 -> showHandshakePasteDialog()
                3 -> showHandshakeStoragePickerDialog()
            }
        }
    }

    private fun showHandshakeUrlDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "https://example.com/capture.cap"
            setPadding(48, 32, 48, 32)
        }
        val megaCheck = android.widget.CheckBox(requireContext()).apply {
            text = "MEGA link"
            setPadding(48, 8, 48, 16)
        }
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(input)
            addView(megaCheck)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Download handshake from URL")
            .setView(layout)
            .setPositiveButton("Download") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                viewModel.loadHandshakeFromUrl(url, megaCheck.isChecked)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showHandshakePasteDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "WPA*01*pmkid*apmac*stamac*essid***\nWPA*02*mic*..."
            setPadding(48, 32, 48, 32)
            minLines = 5
            gravity = android.view.Gravity.TOP
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Paste hash(es)")
            .setMessage("Paste one or more 22000-format hashes")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) return@setPositiveButton
                viewModel.loadHandshakeFromText(text)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showWordlistSourceDialog() {
        val options = listOf(
            SourceOption(R.drawable.ic_folder_open, "From File (.txt)"),
            SourceOption(R.drawable.ic_file_download, "From URL (direct / MEGA / archive)"),
            SourceOption(R.drawable.ic_content_copy, "Paste Password List"),
            SourceOption(R.drawable.cloud_download_24px, "wpa-sec Dictionary (auto-update)"),
            SourceOption(R.drawable.ic_key, "Single Password Test")
        )
        showSourcePickerBottomSheet("Select wordlist source", options) { which ->
            when (which) {
                0 -> wordlistFilePicker.launch(
                    arrayOf(
                        "text/plain",
                        "application/octet-stream",
                        "*/*"
                    )
                )

                1 -> showWordlistUrlDialog()
                2 -> showWordlistPasteDialog()
                3 -> viewModel.useWpaSecDict()
                4 -> showSinglePasswordDialog()
            }
        }
    }

    private fun showWordlistUrlDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "https://example.com/wordlist.txt"
            setPadding(48, 32, 48, 32)
        }
        val megaCheck = android.widget.CheckBox(requireContext()).apply {
            text = "MEGA link"
            setPadding(48, 8, 48, 16)
        }
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(input)
            addView(megaCheck)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Download wordlist from URL")
            .setView(layout)
            .setPositiveButton("Download") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                viewModel.loadWordlistFromUrl(url, megaCheck.isChecked)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showWordlistPasteDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "password1\npassword2\n..."
            setPadding(48, 32, 48, 32)
            minLines = 8
            gravity = android.view.Gravity.TOP
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Paste password list")
            .setMessage("One password per line")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) return@setPositiveButton
                val passwords = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                if (passwords.isEmpty()) {
                    Toast.makeText(requireContext(), "No passwords found", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                viewModel.setWordlistFromPaste(passwords)
                binding.textWordlistInfo.text = "Pasted: ${passwords.size} passwords"
                updateStartButton()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showSinglePasswordDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Enter a single WPA password to test"
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Single Password Test")
            .setMessage("Enter one password to verify against the loaded handshake")
            .setView(input)
            .setPositiveButton("Test") { _, _ ->
                val password = input.text.toString().trim()
                if (password.isBlank()) return@setPositiveButton
                viewModel.trySinglePassword(password)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showHandshakeStoragePickerDialog() {
        val bottomSheet = BottomSheetDialog(requireContext())
        val sheetBinding =
            BottomSheetStoragePickerBinding.inflate(LayoutInflater.from(requireContext()))
        bottomSheet.setContentView(sheetBinding.root)

        sheetBinding.btnPickerClose.setOnClickListener { bottomSheet.dismiss() }

        val allItems = mutableListOf<HandshakeItem>()
        val adapter = StoragePickerAdapter { item ->
            bottomSheet.dismiss()
            resolveAndLoadHandshake(item)
        }
        sheetBinding.recyclerPickerItems.layoutManager = LinearLayoutManager(requireContext())
        sheetBinding.recyclerPickerItems.adapter = adapter

        sheetBinding.editPickerSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.lowercase() ?: ""
                adapter.submitList(
                    if (q.isBlank()) allItems.toList()
                    else allItems.filter {
                        it.essid?.lowercase()?.contains(q) == true ||
                                it.bssid?.lowercase()?.contains(q) == true ||
                                it.fileName.lowercase().contains(q)
                    }
                )
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        sheetBinding.progressPickerLoading.visibility = View.VISIBLE
        sheetBinding.recyclerPickerItems.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val storageManager = HandshakeStorageManager(requireContext())
            val items = try {
                storageManager.listHandshakes()
            } catch (e: Exception) {
                Log.e("WpaCrackerFrag", "Failed to list handshakes", e)
                emptyList()
            }
            allItems.clear()
            allItems.addAll(items)
            withContext(Dispatchers.Main) {
                sheetBinding.progressPickerLoading.visibility = View.GONE
                if (items.isEmpty()) {
                    sheetBinding.textPickerEmpty.visibility = View.VISIBLE
                } else {
                    sheetBinding.recyclerPickerItems.visibility = View.VISIBLE
                    adapter.submitList(items)
                }
            }
        }

        bottomSheet.show()
    }

    private fun resolveAndLoadHandshake(item: HandshakeItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val hashes = resolveHashesFromItem(item)
            if (hashes.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Snackbar.make(
                        binding.root,
                        "No valid hashes found in ${item.fileName}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (hashes.size == 1) {
                    viewModel.loadHandshakeFromStorage(hashes, 0, item.fileName)
                } else {
                    showHashSelectionDialog(item, hashes)
                }
            }
        }
    }

    private fun resolveHashesFromItem(item: HandshakeItem): List<HandshakeHash> {
        val hashText = item.hash22000 ?: item.hashPmkid
        if (hashText != null) {
            val lines = hashText.lines().map { it.trim() }.filter { it.isNotBlank() }
            val parsed = lines.mapNotNull { HandshakeHash.parse22000Line(it) }
            if (parsed.isNotEmpty()) return parsed
        }

        if (!item.fileExists) return emptyList()
        try {
            val hostPath = HandshakeStorageManager.STORAGE_DIR
                .replaceFirst("/sdcard", "/storage/emulated/0")
            val file = File(hostPath, item.fileName)
            if (file.exists()) {
                Log.d("WpaCrackerFrag", "Parsing handshake file: ${file.absolutePath}")
                return HandshakeParser.parseFile(file)
            }
        } catch (e: Exception) {
            Log.e("WpaCrackerFrag", "Failed to parse handshake file: ${item.fileName}", e)
        }
        return emptyList()
    }

    private fun showHashSelectionDialog(item: HandshakeItem, hashes: List<HandshakeHash>) {
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 8)
        }
        val radioGroup = android.widget.RadioGroup(requireContext())
        hashes.indices.forEach { i ->
            val h = hashes[i]
            val typeStr = when (h.type) {
                HandshakeType.PMKID -> "PMKID"
                HandshakeType.EAPOL -> "EAPOL (key ver ${h.keyver ?: 2})"
                HandshakeType.PMKID_EAPOL -> "PMKID+EAPOL"
            }
            val essid = if (h.essid.isNotBlank()) h.essid else "?"
            val extra = if (h.type == HandshakeType.PMKID) h.pmkidOrMic.take(20)
            else "${h.anonce?.take(16) ?: ""} ${h.eapol?.take(16) ?: ""}"
            val radio = RadioButton(requireContext()).apply {
                id = i
                isChecked = i == 0
                setLines(3)
                text = "$essid\n${h.macAp}  $typeStr\n$extra"
                textSize = 14f
            }
            radioGroup.addView(radio)
        }
        layout.addView(radioGroup)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select handshake")
            .setMessage("${item.displayName} — ${hashes.size} hashes found")
            .setView(layout)
            .setPositiveButton("Load") { _, _ ->
                val selected = radioGroup.checkedRadioButtonId
                if (selected >= 0 && selected < hashes.size) {
                    viewModel.loadHandshakeFromStorage(hashes, selected, item.fileName)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showResumeSessionDialog(session: CrackSessionData) {
        val timeAgo = formatTimeAgo(session.timestamp)
        val progress = if (session.totalLines > 0) {
            "${session.offset}/${session.totalLines} (${(session.offset.toDouble() / session.totalLines * 100).toInt()}%)"
        } else {
            "${session.offset} passwords tried"
        }
        val wordlistName = if (session.wordlistUri.isNotBlank()) {
            session.wordlistUri.split("/").lastOrNull() ?: session.wordlistUri
        } else {
            "unknown"
        }
        val msg = buildString {
            appendLine("A previous cracking session matches your handshake and wordlist.")
            appendLine()
            appendLine("Progress: $progress")
            appendLine("Wordlist: $wordlistName")
            appendLine("Engine: ${session.engineName}")
            appendLine("Last active: $timeAgo")
            appendLine()
            append("Resume from where you left off, or discard to start fresh?")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Resume Previous Session?")
            .setMessage(msg)
            .setPositiveButton("Resume") { _, _ ->
                viewModel.restoreSession(session)
            }
            .setNegativeButton("Discard") { _, _ ->
                viewModel.dismissSavedSession()
            }
            .setNeutralButton("Not now", null)
            .show()
    }

    private fun formatTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / 60000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "$days days ago"
            hours > 0 -> "$hours hours ago"
            minutes > 0 -> "$minutes minutes ago"
            else -> "just now"
        }
    }

    private fun runBenchmark() {
        binding.layoutBenchmarkResults.isVisible = false
        binding.textBenchmarkDevice.isVisible = true
        binding.textBenchmarkDevice.text = "Starting benchmark..."
        binding.progressBenchmark.isVisible = true
        binding.buttonRunBenchmark.isEnabled = false
        binding.textBenchmarkDaily.text = ""
        viewModel.runBenchmark()
    }

    private fun setupObservers() {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            handleState(state)
        }
        viewModel.hashResult.observe(viewLifecycleOwner) { result ->
            if (result != null && result.found) {
                showResult(result)
            }
        }
        viewModel.handshakeInfo.observe(viewLifecycleOwner) { info ->
            binding.textHandshakeInfo.text = info
        }
        viewModel.wordlistInfo.observe(viewLifecycleOwner) { info ->
            binding.textWordlistInfo.text = info
            updateStartButton()
        }
        viewModel.isPreparingWordlist.observe(viewLifecycleOwner) {
            updateStartButton()
        }
        viewModel.chrootProgress.observe(viewLifecycleOwner) { progress ->
            if (progress != null) {
                updateChrootProgress(progress)
            }
        }
        viewModel.consoleLines.observe(viewLifecycleOwner) { lines ->
            if (lines.isNotEmpty()) {
                binding.cardChrootConsole.isVisible = true
                if (consoleAdapter == null) {
                    consoleAdapter = ConsoleAdapter(autoScroll = true)
                    binding.recyclerConsole.layoutManager = LinearLayoutManager(requireContext())
                    binding.recyclerConsole.adapter = consoleAdapter
                    consoleAdapter?.attachToRecyclerView(binding.recyclerConsole)
                }
                consoleAdapter?.setLines(lines)
            }
        }
        viewModel.benchmarkResult.observe(viewLifecycleOwner) { report ->
            if (report != null) {
                showBenchmarkReport(report)
            }
        }
        viewModel.benchmarkRunning.observe(viewLifecycleOwner) { running ->
            binding.progressBenchmark.isVisible = running
            if (!running) {
                binding.buttonRunBenchmark.isEnabled = true
            }
        }
        viewModel.benchmarkProgress.observe(viewLifecycleOwner) { progress ->
            if (progress != null) {
                updateBenchmarkProgress(progress)
            }
        }
        viewModel.isPaused.observe(viewLifecycleOwner) { paused ->
            binding.buttonPauseResume.text = if (paused) "Resume" else "Pause"
        }
        viewModel.isRunningInBackground.observe(viewLifecycleOwner) { bg ->
            binding.textBackgroundIndicator.isVisible = bg
        }
        viewModel.savedSession.observe(viewLifecycleOwner) { session ->
            if (session != null && isResumed) {
                showResumeSessionDialog(session)
            }
        }
    }

    private fun updateBenchmarkProgress(progress: BenchmarkProgress) {
        binding.textBenchmarkDevice.isVisible = true
        val text = buildString {
            append(progress.stage)
            if (progress.subProgress.isNotEmpty()) {
                append(" — ${progress.subProgress}")
            }
            if (progress.percent >= 0) {
                append(" [${progress.percent}%]")
            }
        }
        binding.textBenchmarkDevice.text = text
    }

    private fun handleState(state: WpaCrackerState) {
        when (state) {
            is WpaCrackerState.Idle -> {
                binding.cardNativeProgress.isVisible = false
                binding.cardChrootConsole.isVisible = false
                binding.cardChrootProgress.isVisible = false
                binding.cardResult.isVisible = false
                binding.buttonCancel.isVisible = false
                binding.buttonStartCrack.text = "Start Cracking"
                binding.buttonStartCrack.visibility = View.VISIBLE
                binding.buttonStartCrack.isEnabled = false
                binding.layoutHandshakeDetails.isVisible = false
                binding.buttonPauseResume.isVisible = false
                binding.buttonStopCrack.isVisible = false
                binding.textBackgroundIndicator.isVisible = false
                consoleAdapter = null
            }

            is WpaCrackerState.LoadingHandshake -> {
                binding.textHandshakeInfo.text = "Loading..."
            }

            is WpaCrackerState.LoadingWordlist -> {}
            is WpaCrackerState.Loaded -> {
                updateHandshakeInfo(state.hash, state.fileName)
                updateStartButton()
            }

            is WpaCrackerState.Cracking -> {
                Log.d("WpaCrackerFrag", "Cracking state: ${state.progress.attempts}")
                showNativeProgress(state.progress)
                binding.buttonStartCrack.visibility = View.GONE
                binding.buttonPauseResume.isVisible = true
                binding.buttonPauseResume.text = "Pause"
                binding.buttonStopCrack.isVisible = true
                binding.buttonCancel.isVisible = false
            }

            is WpaCrackerState.Paused -> {
                if (state.progress.attempts > 0 || state.progress.currentPassword.isNotEmpty()) {
                    showNativeProgress(state.progress)
                }
                binding.buttonPauseResume.isVisible = true
                binding.buttonPauseResume.text = "Resume"
                binding.buttonStopCrack.isVisible = true
                binding.buttonStartCrack.visibility = View.GONE
                binding.buttonCancel.isVisible = false
            }

            is WpaCrackerState.ChrootCracking -> {
                binding.cardChrootConsole.isVisible = true
                binding.buttonStartCrack.text = "Stop"
                binding.buttonStartCrack.isEnabled = true
                binding.buttonStartCrack.visibility = View.VISIBLE
                binding.buttonCancel.isVisible = false
                binding.buttonPauseResume.isVisible = false
                binding.buttonStopCrack.isVisible = false
            }

            is WpaCrackerState.Done -> {
                binding.cardNativeProgress.isVisible = false
                binding.cardChrootProgress.isVisible = false
                binding.buttonCancel.isVisible = false
                binding.buttonPauseResume.isVisible = false
                binding.buttonStopCrack.isVisible = false
                binding.textBackgroundIndicator.isVisible = false
                binding.buttonStartCrack.text = "Start Cracking"
                binding.buttonStartCrack.visibility = View.VISIBLE
                binding.buttonStartCrack.isEnabled = true
                val found = state.result.foundPassword
                if (found != null) {
                    showPasswordFound(found)
                } else {
                    showCrackFailed(state.result)
                }
            }

            is WpaCrackerState.Error -> {
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                binding.cardNativeProgress.isVisible = false
                binding.cardChrootConsole.isVisible = false
                binding.cardChrootProgress.isVisible = false
                binding.buttonCancel.isVisible = false
                binding.buttonPauseResume.isVisible = false
                binding.buttonStopCrack.isVisible = false
                binding.textBackgroundIndicator.isVisible = false
                binding.buttonStartCrack.text = "Start Cracking"
                binding.buttonStartCrack.visibility = View.VISIBLE
            }
        }
    }

    private fun showNativeProgress(progress: OfflineProgress) {
        binding.cardNativeProgress.isVisible = true
        if (progress.attempts == 0L && progress.currentPassword.isBlank()) {
            binding.textProgressPassword.text = "Starting crack engine..."
            binding.textProgressStats.text =
                "Counting wordlist and preparing chunks, please wait..."
            binding.progressBar.isIndeterminate = true
            return
        }
        binding.textProgressPassword.text = "Trying: ${progress.currentPassword}"
        val speed = "%.0f".format(progress.speed)
        val elapsed = formatTime(progress.elapsedMs)
        val eta = if (progress.etaMs > 0 && progress.etaMs < Long.MAX_VALUE) {
            " | ETA: ${formatTime(progress.etaMs)}"
        } else ""
        binding.textProgressStats.text =
            "Attempts: ${progress.attempts}/${progress.totalPasswords} | Speed: $speed pw/s | Elapsed: $elapsed$eta"
        if (progress.totalPasswords > 0) {
            val pct = (progress.attempts.toFloat() / progress.totalPasswords * 100).toInt()
            binding.progressBar.isIndeterminate = false
            binding.progressBar.setProgress(pct, true)
        }
    }

    private fun updateChrootProgress(progress: ChrootCrackProgress) {
        binding.cardChrootProgress.isVisible = true
        val pct = progress.percent
        if (pct >= 0) {
            binding.chrootProgressBar.isIndeterminate = false
            binding.chrootProgressBar.max = 10000
            binding.chrootProgressBar.setProgress((pct * 100).toInt().coerceIn(0, 10000), true)
        } else {
            binding.chrootProgressBar.isIndeterminate = true
        }
        binding.chrootTextPassword.text = if (progress.currentPassword.isNotBlank()) {
            "Trying: ${progress.currentPassword}"
        } else {
            "Starting..."
        }
        binding.chrootTextAttempts.text = if (progress.total > 0) {
            "Attempts: ${progress.attempts}/${progress.total}"
        } else {
            ""
        }
        binding.chrootTextSpeed.text = if (progress.speed.isNotBlank()) {
            "Speed: ${progress.speed}"
        } else {
            ""
        }
        binding.chrootTextEta.text = if (progress.eta.isNotBlank()) {
            "ETA: ${progress.eta}"
        } else {
            ""
        }
    }

    private fun updateHandshakeInfo(hash: HandshakeHash, fileName: String) {
        binding.layoutHandshakeDetails.isVisible = true
        binding.textEssid.text = "ESSID: ${hash.essid}"
        binding.textBssid.text = "BSSID: ${hash.macAp}"
        val typeStr = when (hash.type) {
            HandshakeType.PMKID -> "PMKID"
            HandshakeType.EAPOL -> "EAPOL (keyver ${hash.keyver ?: "?"})"
            HandshakeType.PMKID_EAPOL -> "PMKID+EAPOL"
        }
        binding.textHashType.text = "Type: $typeStr"
    }

    private fun updateStartButton() {
        val hasHandshake = viewModel.state.value is WpaCrackerState.Loaded
        val hasWordlist = binding.textWordlistInfo.text != "Tap to select wordlist source"
        val isPreparing = viewModel.isPreparingWordlist.value ?: false
        binding.buttonStartCrack.isEnabled = hasHandshake && hasWordlist && !isPreparing
    }

    private fun showPasswordFound(password: String) {
        binding.cardResult.isVisible = true
        binding.textResultTitle.text = "FOUND!"
        binding.textResultTitle.setTextColor(
            ResourcesCompat.getColor(resources, R.color.success_green, null)
        )
        binding.textResultPassword.text = password
        binding.textResultStats.text = "Password saved to local database and handshake storage"
        binding.buttonCopyPassword.isVisible = true
    }

    private fun showCrackFailed(result: OfflineResult) {
        binding.cardResult.isVisible = true
        binding.textResultTitle.text = "Not Found"
        binding.textResultTitle.setTextColor(
            ResourcesCompat.getColor(resources, R.color.error_red, null)
        )
        binding.textResultPassword.text = "Password not found in wordlist"
        binding.textResultStats.text =
            "Attempts: ${result.attempts} | Time: ${formatTime(result.elapsedMs)} | Avg speed: ${
                "%.1f".format(result.averageSpeed)
            } pw/s"
        binding.buttonCopyPassword.isVisible = false
    }

    private fun showResult(result: WpaCracker.CrackerResult) {
        binding.cardResult.isVisible = true
        binding.textResultTitle.text = "FOUND!"
        binding.textResultTitle.setTextColor(
            ResourcesCompat.getColor(resources, R.color.success_green, null)
        )
        binding.textResultPassword.text = result.password ?: "unknown"
        binding.textResultStats.text = listOfNotNull(
            result.pmk?.let { "PMK: ${it.take(16)}..." },
            result.ptk?.let { "PTK: ${it.take(16)}..." },
            result.mic?.let { "MIC: ${it.take(16)}..." },
            "Keyver: ${result.keyver}"
        ).joinToString("\n")
        binding.buttonCopyPassword.isVisible = true
    }

    private fun showBenchmarkReport(report: WpaBenchmark.Report) {
        binding.textBenchmarkDevice.text = "Device: ${report.deviceName}"
        binding.textBenchmarkDevice.isVisible = true
        binding.layoutBenchmarkResults.isVisible = true

        val multiResults = mutableListOf<String>()
        for (r in report.results) {
            val text = "%s: %s (%s)".format(r.name, r.speedFormatted, r.elapsedFormatted)
            when {
                r.name == "PMKID (PBKDF2+HMAC) native" -> binding.textBenchmarkPbkdf2.text = text
                r.name == "PMKID (Kotlin fallback)" -> binding.textBenchmarkPmkid.text = text
                r.name == "EAPOL keyver 1 (HMAC-MD5)" -> binding.textBenchmarkEapol1.text = text
                r.name == "EAPOL keyver 2 (HMAC-SHA1)" -> binding.textBenchmarkEapol2.text = text
                r.name == "EAPOL keyver 3 (AES-CMAC)" -> binding.textBenchmarkEapol3.text = text
                r.name.startsWith("Multi-Thread") -> multiResults.add(text)
                r.name.startsWith("Chroot") -> {
                    binding.textBenchmarkChroot.text = text
                    if (r.speed <= 0) {
                        binding.textBenchmarkChroot.setTextColor(
                            ResourcesCompat.getColor(resources, R.color.error_red, null)
                        )
                    } else {
                        binding.textBenchmarkChroot.setTextColor(
                            ResourcesCompat.getColor(resources, R.color.success_green, null)
                        )
                    }
                }
            }
        }
        binding.textBenchmarkMulti1.text = multiResults.getOrElse(0) { "" }
        binding.textBenchmarkMulti2.text = multiResults.getOrElse(1) { "" }
        binding.textBenchmarkMulti4.text =
            multiResults.getOrElse(2) { "" } + "\n" + multiResults.getOrElse(3) { "" }

        binding.textBenchmarkDaily.text = "Estimated: ${report.estimatedDaily}"
    }

    private fun formatTime(ms: Long): String {
        if (ms < 1000) return "${ms}ms"
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return if (hours > 0) "%dh %02dm %02ds".format(hours, mins, secs)
        else if (mins > 0) "%dm %02ds".format(mins, secs)
        else "%ds".format(secs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
