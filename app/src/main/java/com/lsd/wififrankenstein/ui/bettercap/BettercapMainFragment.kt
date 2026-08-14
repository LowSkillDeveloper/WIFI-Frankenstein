package com.lsd.wififrankenstein.ui.bettercap

import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentBettercapMainBinding
import com.lsd.wififrankenstein.network.bettercap.DaemonStatus
import com.lsd.wififrankenstein.ui.bettercap.dashboard.BettercapDashboardAdapter
import com.lsd.wififrankenstein.ui.bettercap.dashboard.SortMode
import com.lsd.wififrankenstein.ui.bettercap.eventlog.BettercapEventAdapter
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BettercapMainFragment : Fragment() {

    private var _binding: FragmentBettercapMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BettercapViewModel by activityViewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return BettercapViewModel(requireActivity().application) as T
            }
        }
    }

    private lateinit var apAdapter: BettercapDashboardAdapter
    private lateinit var eventAdapter: BettercapEventAdapter
    private var currentIface = "wlan0"
    private var eventsAutoScrollEnabled = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBettercapMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupApList()
        setupEventList()
        currentIface =
            IwWifiManager(requireContext()).getSavedCaptureInterface() ?: currentIface
        setupInterfaceSpinner()
        setupChannelSpinner()
        setupSortDropdown()
        setupFilters()
        setupButtons()
        setupSettings()
        observeViewModel()

        lifecycleScope.launchWhenStarted {
            viewModel.loadInterfaces()
        }

        lifecycleScope.launch {
            delay(1000)
            viewModel.checkLeftoverBettercapCaptures()
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.viewFlipper.displayedChild = tab.position
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupApList() {
        apAdapter = BettercapDashboardAdapter(
            onItemClick = { ap ->
                viewModel.selectAp(ap)
                try {
                    findNavController().navigate(R.id.action_bettercap_main_to_detail)
                } catch (_: Exception) {
                }
            },
            onDeauthAll = { ap -> viewModel.deauthAp(ap.mac) },
            onAssoc = { ap -> viewModel.assoc(ap.mac) }
        )
        binding.recyclerViewAps.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = apAdapter
        }
    }

    private fun setupEventList() {
        eventAdapter = BettercapEventAdapter()
        binding.recyclerViewEvents.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = eventAdapter
        }

        binding.fabScrollToBottom.setOnClickListener {
            eventsAutoScrollEnabled = true
            val count = eventAdapter.itemCount
            if (count > 0) binding.recyclerViewEvents.smoothScrollToPosition(count - 1)
            binding.fabScrollToBottom.hide()
        }

        binding.recyclerViewEvents.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val total = recyclerView.adapter?.itemCount ?: 0
                val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
                val atBottom = total == 0 || lastVisible < 0 || lastVisible >= total - 1
                eventsAutoScrollEnabled = atBottom
                if (atBottom) binding.fabScrollToBottom.hide()
                else binding.fabScrollToBottom.show()
            }
        })
    }

    private fun channelOptions(): Array<String> = arrayOf(
        getString(R.string.bc_channel_all_hop),
        getString(R.string.bc_channel_single),
        getString(R.string.bc_channel_multi),
        getString(R.string.bc_channel_custom)
    )
    private val ALL_CHANNELS = listOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
        36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116,
        120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165
    )
    private var channelSpinnerInitialized = false

    private fun setupChannelSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            channelOptions()
        )
        binding.spinnerChannel.adapter = adapter
        binding.spinnerChannel.setSelection(0)
        channelSpinnerInitialized = false
        binding.spinnerChannel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (!channelSpinnerInitialized) {
                        channelSpinnerInitialized = true
                        return
                    }
                    when (position) {
                        0 -> viewModel.setChannelsAndMode(emptyList())
                        1 -> showSingleChannelDialog()
                        2 -> showMultiChannelDialog()
                        3 -> showCustomChannelDialog()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun channelLabel(c: Int): String {
        val freq = when {
            c == 14 -> 2484
            c <= 14 -> 2412 + (c - 1) * 5
            c <= 64 -> 5180 + (c - 36) * 20
            c <= 144 -> 5500 + (c - 100) * 5
            else -> 5745 + (c - 149) * 5
        }
        return getString(R.string.bc_channel_label, c, freq)
    }

    private fun showSingleChannelDialog() {
        val names = ALL_CHANNELS.map { channelLabel(it) }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bc_select_channel_single)
            .setSingleChoiceItems(names, -1) { dialog, which ->
                val ch = ALL_CHANNELS[which]
                viewModel.setChannelsAndMode(listOf(ch))
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMultiChannelDialog() {
        val names = ALL_CHANNELS.map { channelLabel(it) }.toTypedArray()
        val checked = BooleanArray(ALL_CHANNELS.size)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bc_select_channels_multi)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.bc_apply) { dialog, _ ->
                val selected = mutableListOf<Int>()
                for (i in checked.indices) {
                    if (checked[i]) selected.add(ALL_CHANNELS[i])
                }
                if (selected.isNotEmpty()) {
                    viewModel.setChannelsAndMode(selected)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCustomChannelDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.bc_custom_channels_hint)
            setLines(2)
            textSize = 14f
        }
        val msg = getString(R.string.bc_custom_channels_message)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bc_channel_custom)
            .setMessage(msg)
            .setView(input)
            .setPositiveButton(R.string.bc_apply) { dialog, _ ->
                val text = input.text.toString()
                val channels = text.split(Regex("[,\\s]+")).mapNotNull { it.toIntOrNull() }
                    .filter { it in 1..233 }
                if (channels.isNotEmpty()) {
                    viewModel.setChannelsAndMode(channels)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupInterfaceSpinner() {
        lifecycleScope.launchWhenStarted {
            try {
                val iw = IwWifiManager(requireContext())
                val ifaces = iw.getAvailableInterfaces()
                val names = ifaces.map { it.name }.toTypedArray()
                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    names
                )
                binding.spinnerInterfaceHeader.adapter = adapter
                val idx = names.indexOfFirst { it == currentIface }.coerceAtLeast(0)
                binding.spinnerInterfaceHeader.setSelection(idx)
                val iwManager = iw
                binding.spinnerInterfaceHeader.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                        ) {
                            val selected =
                                parent?.getItemAtPosition(position) as? String ?: return
                            currentIface = selected
                            iwManager.saveCaptureInterface(selected.removeSuffix("mon"))
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
            } catch (_: Exception) {
            }
        }
    }

    private fun setupSortDropdown() {
        val sortModes = SortMode.entries.map { getString(it.labelRes) }.toTypedArray()
        val adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, sortModes)
        binding.spinnerSort.adapter = adapter
        binding.spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val mode = SortMode.entries.getOrNull(position) ?: SortMode.NAME_ASC
                apAdapter.setSortMode(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFilters() {
        binding.chipFilterClients.setOnCheckedChangeListener { _, isChecked ->
            apAdapter.setFilterClientsOnly(isChecked)
        }
        binding.chipGroupEventFilter.setOnCheckedStateChangeListener { group, _ ->
            val filter = when {
                group.findViewById<Chip>(R.id.chipEventAp)?.isChecked == true -> "wifi.ap"
                group.findViewById<Chip>(R.id.chipEventHandshake)?.isChecked == true -> "handshake"
                group.findViewById<Chip>(R.id.chipEventDeauth)?.isChecked == true -> "deauth"
                else -> null
            }
            eventAdapter.setFilter(filter)
        }
    }

    private fun setupButtons() {
        binding.buttonToggleScan.setOnClickListener {
            when (viewModel.daemonStatus.value) {
                DaemonStatus.STOPPED, DaemonStatus.ERROR -> {
                    viewModel.startDaemon(currentIface)
                }

                DaemonStatus.RUNNING, DaemonStatus.STARTING, DaemonStatus.RESTARTING -> {
                    viewModel.stopDaemon()
                }

                else -> {}
            }
        }
        binding.buttonClearLog.setOnClickListener {
            viewModel.clearEventLog()
        }
        binding.buttonDeauthAll.setOnClickListener {
            viewModel.deauthAll()
            Toast.makeText(requireContext(), R.string.bc_deauth_all_aps, Toast.LENGTH_SHORT).show()
        }
        binding.buttonAssocAll.setOnClickListener {
            viewModel.assocAll()
            Toast.makeText(requireContext(), R.string.bc_assoc_all_aps, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSettings() {
        binding.seekBarHopPeriod.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.textHopPeriodValue.text = getString(R.string.bc_hop_period_value, (progress + 1) * 50)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val ms = ((seekBar?.progress ?: 4) + 1) * 50
                if (viewModel.daemonStatus.value == DaemonStatus.RUNNING) {
                    viewModel.setConfig("wifi.hop.period", ms.toString())
                }
            }
        })
        binding.seekBarMinRssi.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.textMinRssiValue.text =
                    getString(R.string.bc_min_rssi_value, -(progress + 1) * 5 - 25)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val rssi = -((seekBar?.progress ?: 13) + 1) * 5 - 25
                if (viewModel.daemonStatus.value == DaemonStatus.RUNNING) {
                    viewModel.setConfig("wifi.rssi.min", rssi.toString())
                }
            }
        })
        binding.switchHopping.setOnCheckedChangeListener { _, isChecked ->
            val ch = viewModel.selectedChannels.value ?: emptyList()
            if (isChecked && ch.isNotEmpty()) viewModel.setChannelsAndMode(emptyList())
            else if (!isChecked && ch.isEmpty()) viewModel.setChannelsAndMode(listOf(6))
        }
        binding.switchDeauthOpen.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.daemonStatus.value == DaemonStatus.RUNNING)
                viewModel.setConfig("wifi.deauth.open", if (isChecked) "true" else "false")
        }
        binding.switchDeauthAcquired.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.daemonStatus.value == DaemonStatus.RUNNING)
                viewModel.setConfig("wifi.deauth.acquired", if (isChecked) "true" else "false")
        }
        binding.switchAssocOpen.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.daemonStatus.value == DaemonStatus.RUNNING)
                viewModel.setConfig("wifi.assoc.open", if (isChecked) "true" else "false")
        }
        binding.switchAssocAcquired.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.daemonStatus.value == DaemonStatus.RUNNING)
                viewModel.setConfig("wifi.assoc.acquired", if (isChecked) "true" else "false")
        }
    }

    private var interfaceMonitorJob: kotlinx.coroutines.Job? = null

    private fun updateStatusDot(daemonStatus: DaemonStatus, interfaceOnline: Boolean = true) {
        val drawableRes = when {
            daemonStatus == DaemonStatus.RUNNING && interfaceOnline -> R.drawable.circle_green
            daemonStatus == DaemonStatus.ERROR || !interfaceOnline -> R.drawable.circle_red
            else -> R.drawable.circle_gray
        }
        binding.statusDot.setBackgroundResource(drawableRes)
    }

    private fun observeViewModel() {
        viewModel.wifiApList.observe(viewLifecycleOwner) { aps ->
            apAdapter.updateData(aps)
            binding.textEmptyState.visibility =
                if (aps.isEmpty() && viewModel.daemonStatus.value == DaemonStatus.RUNNING)
                    View.VISIBLE else View.GONE
        }
        viewModel.daemonStatus.observe(viewLifecycleOwner) { status ->
            binding.buttonToggleScan.text = when (status) {
                DaemonStatus.RUNNING -> getString(R.string.bc_stop)
                DaemonStatus.STARTING, DaemonStatus.RESTARTING -> "..."
                else -> getString(R.string.bc_start)
            }
            val isRunning = status == DaemonStatus.RUNNING || status == DaemonStatus.STARTING

            binding.spinnerInterfaceHeader.visibility = if (isRunning) View.GONE else View.VISIBLE
            binding.layoutInterfaceStatus.visibility = if (isRunning) View.VISIBLE else View.GONE
            updateStatusDot(status)
            binding.textInterfaceName.text = currentIface


            if (status == DaemonStatus.RUNNING) {
                startInterfaceMonitor()
            } else {
                interfaceMonitorJob?.cancel()
                interfaceMonitorJob = null
            }
        }

        viewModel.apCount.observe(viewLifecycleOwner) { count ->
            val hs = viewModel.handshakeCount.value ?: 0
            binding.textStats.text = getString(R.string.bc_stats, count, hs)
        }
        viewModel.handshakeCount.observe(viewLifecycleOwner) { hs ->
            val count = viewModel.apCount.value ?: 0
            binding.textStats.text = getString(R.string.bc_stats, count, hs)
        }
        viewModel.eventLog.observe(viewLifecycleOwner) { events ->
            eventAdapter.updateData(events)
            val isEmpty = events.isEmpty()
            binding.recyclerViewEvents.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.textNoEvents.visibility = if (isEmpty) View.VISIBLE else View.GONE
            if (!isEmpty && eventsAutoScrollEnabled) {
                binding.recyclerViewEvents.scrollToPosition(events.size - 1)
            }
        }
        viewModel.interfaces.observe(viewLifecycleOwner) { ifaces ->
            if (ifaces.isNotEmpty()) {
                if (ifaces.none { it.name == currentIface }) {
                    currentIface = ifaces.first().name
                }
                binding.textInterfaceName.text = currentIface
            }
        }
        viewModel.daemonIface.observe(viewLifecycleOwner) { monIface ->
            if (monIface != null && viewModel.daemonStatus.value == DaemonStatus.RUNNING) {
                currentIface = monIface
                binding.textInterfaceName.text = monIface
                syncInterfaceSpinnerSelection(monIface)
            }
        }
        viewModel.sessionResults.observe(viewLifecycleOwner) { results ->
            if (results != null && results.isNotEmpty()) {
                showSessionResultsDialog(results)
            }
        }
        viewModel.leftoverCaptures.observe(viewLifecycleOwner) { captures ->
            if (captures != null && captures.isNotEmpty()) {
                showLeftoverCapturesDialog(captures)
            }
        }
        viewModel.commandError.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                viewModel.consumeCommandError()
            }
        }
    }

    private fun showSessionResultsDialog(results: List<BettercapViewModel.BettercapCaptureResult>) {
        val valid = results.count { it.isValid }
        val invalid = results.count { !it.isValid }
        val summary =
            getString(R.string.bettercap_session_results_message, results.size, valid, invalid)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bettercap_session_results_title)
            .setView(buildResultsDialogView(results, summary))
            .setPositiveButton(R.string.bettercap_session_save_valid) { _, _ ->
                viewModel.saveSelectedResults(results.filter { it.isValid })
            }
            .setNeutralButton(R.string.bettercap_session_save_all) { _, _ ->
                viewModel.saveSelectedResults(results)
            }
            .setCancelable(false)
            .show()
    }

    private fun showLeftoverCapturesDialog(captures: List<BettercapViewModel.BettercapCaptureResult>) {
        val valid = captures.count { it.isValid }
        val invalid = captures.count { !it.isValid }
        val summary = getString(R.string.bettercap_leftover_message, captures.size, valid, invalid)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bettercap_leftover_title)
            .setView(buildResultsDialogView(captures, summary))
            .setPositiveButton(R.string.bettercap_session_save_valid) { _, _ ->
                viewModel.saveSelectedResults(captures.filter { it.isValid })
            }
            .setNeutralButton(R.string.bettercap_session_save_all) { _, _ ->
                viewModel.saveSelectedResults(captures)
            }
            .setCancelable(false)
            .show()
    }

    private fun buildResultsDialogView(
        results: List<BettercapViewModel.BettercapCaptureResult>,
        summary: String
    ): View {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_bettercap_session_results, null)
        view.findViewById<TextView>(R.id.textSessionSummary)?.text = summary
        val list = view.findViewById<LinearLayout>(R.id.layoutResultsList)
        for (r in results) {
            list.addView(buildResultRow(r))
        }
        return view
    }

    private fun buildResultRow(r: BettercapViewModel.BettercapCaptureResult): TextView {
        val ctx = requireContext()
        val tv = TextView(ctx)
        val density = ctx.resources.displayMetrics.density
        tv.setPadding(
            (8 * density).toInt(), (4 * density).toInt(),
            (8 * density).toInt(), (4 * density).toInt()
        )
        tv.textSize = 13f
        tv.setTextIsSelectable(true)

        val isValid = r.isValid
        val color = ContextCompat.getColor(
            ctx,
            if (isValid) R.color.success_green else R.color.error_red
        )
        val icon = if (isValid) "\u2713 " else "\u2717 "
        val hostname = r.ap.hostname.ifEmpty { r.ap.mac }
        val info = if (isValid) getString(R.string.bc_eapol_pmkid, r.eapolCount, r.pmkidCount)
        else (r.error ?: getString(R.string.bc_no_data))

        val spannable = SpannableStringBuilder()
        spannable.append(icon)
        val start = spannable.length
        spannable.append(hostname)
        spannable.setSpan(
            StyleSpan(Typeface.BOLD), start, spannable.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.append(getString(R.string.bc_result_row_info, r.ap.mac, info))
        tv.text = spannable
        tv.setTextColor(color)
        return tv
    }

    private fun syncInterfaceSpinnerSelection(iface: String) {
        try {
            val adapter = binding.spinnerInterfaceHeader.adapter ?: return
            for (i in 0 until adapter.count) {
                if (adapter.getItem(i).toString() == iface) {
                    binding.spinnerInterfaceHeader.setSelection(i)
                    return
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun startInterfaceMonitor() {
        interfaceMonitorJob?.cancel()
        interfaceMonitorJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                try {
                    val iw = IwWifiManager(requireContext())
                    val ifaces = iw.getAvailableInterfaces()
                    val found = ifaces.any { it.name == currentIface }
                    binding.textInterfaceName.text = currentIface
                    val status = viewModel.daemonStatus.value
                    if (status == DaemonStatus.RUNNING) {
                        updateStatusDot(status, found)
                    }
                } catch (_: Exception) {
                }
                delay(10_000)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        interfaceMonitorJob?.cancel()
        interfaceMonitorJob = null
        _binding = null
    }
}
