package com.lsd.wififrankenstein.ui.netprotection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.widget.SwitchCompat
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentNetProtectionBinding
import com.lsd.wififrankenstein.service.NetProtectionService

class NetProtectionFragment : Fragment() {

    private var _binding: FragmentNetProtectionBinding? = null
    private val binding get() = _binding!!
    private val eventAdapter = EventLogAdapter()
    private var isServiceRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            isServiceRunning = intent?.getBooleanExtra(NetProtectionService.EXTRA_STATUS_RUNNING, false) ?: false
            updateMasterStatus()
        }
    }

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(NetProtectionService.EXTRA_EVENT_TEXT) ?: return
            val typeName = intent.getStringExtra(NetProtectionService.EXTRA_EVENT_TYPE) ?: "INFO"
            val type = try { EventType.valueOf(typeName) } catch (_: Exception) { EventType.INFO }
            val event = NetProtectionEvent(System.currentTimeMillis(), type, text)
            eventAdapter.addEvent(event)
            binding.emptyLog.visibility = View.GONE
            binding.eventLog.visibility = View.VISIBLE
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNetProtectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMasterSwitch()
        setupNotificationSettings()
        setupEventLog()
        updateBanner()
    }

    override fun onStart() {
        super.onStart()
        val statusFilter = IntentFilter(NetProtectionService.BROADCAST_STATUS)
        val eventFilter = IntentFilter(NetProtectionService.BROADCAST_EVENT)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(statusReceiver, statusFilter)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(eventReceiver, eventFilter)
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(statusReceiver)
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(eventReceiver)
    }

    private fun setupMasterSwitch() {
        binding.arpSwitch.isChecked = true
        binding.portScanSwitch.isChecked = true
        binding.connectionSwitch.isChecked = true

        binding.masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.arpSwitch.isChecked = true
                binding.portScanSwitch.isChecked = true
                binding.connectionSwitch.isChecked = true
                startService()
            } else {
                stopService()
            }
        }

        binding.arpSwitch.setOnCheckedChangeListener { _, _ -> sendConfigUpdate() }
        binding.portScanSwitch.setOnCheckedChangeListener { _, _ -> sendConfigUpdate() }
        binding.connectionSwitch.setOnCheckedChangeListener { _, _ -> sendConfigUpdate() }
    }

    private fun setupNotificationSettings() {
        val prefs = requireContext().getSharedPreferences("net_protection", Context.MODE_PRIVATE)
        val showNotif = prefs.getBoolean("show_notification", true)
        val priority = prefs.getInt("notification_priority", 1)

        binding.notificationSwitch.isChecked = showNotif
        updateNotificationSwitchState(showNotif)

        binding.notificationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                showHideNotificationDialog()
            } else {
                saveNotificationPref(true)
                updateNotificationSwitchState(true)
                sendConfigUpdate()
            }
        }

        val priorityLabels = arrayOf(
            getString(R.string.np_notification_min),
            getString(R.string.np_notification_low),
            getString(R.string.np_notification_default)
        )
        val priorityAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, priorityLabels)
        priorityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.notificationPrioritySpinner.adapter = priorityAdapter
        binding.notificationPrioritySpinner.setSelection(priority)

        binding.notificationPrioritySpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    saveNotificationPriority(pos)
                    sendConfigUpdate()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun showHideNotificationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.np_hide_notification_title))
            .setMessage(getString(R.string.np_hide_notification_message))
            .setPositiveButton(getString(R.string.np_hide_notification_confirm)) { _, _ ->
                saveNotificationPref(false)
                updateNotificationSwitchState(false)
                sendConfigUpdate()
            }
            .setNegativeButton(getString(R.string.np_hide_notification_cancel)) { _, _ ->
                binding.notificationSwitch.isChecked = true
            }
            .show()
    }

    private fun updateNotificationSwitchState(show: Boolean) {
        binding.notificationPrioritySpinner.alpha = if (show) 1f else 0.4f
        binding.notificationPrioritySpinner.isEnabled = show
        binding.notificationPriorityLabel.alpha = if (show) 1f else 0.4f
    }

    private fun saveNotificationPref(show: Boolean) {
        requireContext().getSharedPreferences("net_protection", Context.MODE_PRIVATE)
            .edit().putBoolean("show_notification", show).apply()
    }

    private fun saveNotificationPriority(priority: Int) {
        requireContext().getSharedPreferences("net_protection", Context.MODE_PRIVATE)
            .edit().putInt("notification_priority", priority).apply()
    }

    private fun setupEventLog() {
        binding.eventLog.layoutManager = LinearLayoutManager(requireContext())
        binding.eventLog.adapter = eventAdapter
    }

    private fun startService() {
        NetProtectionService.start(requireContext())
        isServiceRunning = true
        updateMasterStatus()
    }

    private fun stopService() {
        NetProtectionService.stop(requireContext())
        isServiceRunning = false
        updateMasterStatus()
    }

    private fun sendConfigUpdate() {
        if (!isServiceRunning) return
        val prefs = requireContext().getSharedPreferences("net_protection", Context.MODE_PRIVATE)
        NetProtectionService.updateConfig(
            requireContext(),
            arpEnabled = binding.arpSwitch.isChecked,
            portScanEnabled = binding.portScanSwitch.isChecked,
            connectionMonitorEnabled = binding.connectionSwitch.isChecked,
            notificationHidden = !binding.notificationSwitch.isChecked,
            notificationPriority = binding.notificationPrioritySpinner.selectedItemPosition
        )
    }

    private fun updateMasterStatus() {
        binding.masterStatus.text = if (isServiceRunning) {
            getString(R.string.np_status_active)
        } else {
            getString(R.string.np_status_inactive)
        }
        binding.masterStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (isServiceRunning) R.color.success_green else R.color.error_red
            )
        )
    }

    private fun updateBanner() {
        val result = CapabilityDetector.detect(requireContext())
        val bannerText = when (result.overallLevel) {
            com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.FULL ->
                getString(R.string.np_banner_full)
            com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.LIMITED ->
                getString(R.string.np_banner_limited)
            com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.UNAVAILABLE ->
                getString(R.string.np_banner_limited)
        }
        binding.bannerText.text = bannerText

        val color = when (result.overallLevel) {
            com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.FULL ->
                ContextCompat.getColor(requireContext(), R.color.success_green)
            else -> ContextCompat.getColor(requireContext(), R.color.error_red)
        }
        binding.bannerText.setTextColor(color)

        val arpDetail = when {
            result.arpCapability == com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.FULL && CapabilityDetector.canReadProcArp() ->
                getString(R.string.np_arp_detail_full)
            result.arpCapability == com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.FULL ->
                getString(R.string.np_arp_detail_ipneigh)
            else -> getString(R.string.np_arp_detail_limited)
        }
        binding.arpMethod.text = "${getString(R.string.np_method, arpDetail)}"

        val portDetail = when {
            result.portScanCapability == com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.FULL && CapabilityDetector.canReadProcSnmp() ->
                getString(R.string.np_port_detail_snmp)
            result.portScanCapability == com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.LIMITED ->
                getString(R.string.np_port_detail_traffic)
            else -> getString(R.string.np_port_detail_limited)
        }
        binding.portScanMethod.text = "${getString(R.string.np_method, portDetail)}"

        binding.portScanSource.visibility = View.GONE

        val connDetail = when {
            result.connectionMonitorCapability == com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.FULL && CapabilityDetector.canReadProcTcp() ->
                getString(R.string.np_conn_detail_proc)
            result.connectionMonitorCapability == com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.LIMITED ->
                getString(R.string.np_conn_detail_traffic)
            else -> getString(R.string.np_conn_detail_limited)
        }
        binding.connectionMethod.text = "${getString(R.string.np_method, connDetail)}"

        if (result.isRoot) {
            binding.bannerIcon.text = "\uD83D\uDD13"
        } else if (result.overallLevel == com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.LIMITED) {
            binding.bannerIcon.text = "\u26A0\uFE0F"
        } else {
            binding.bannerIcon.text = "\uD83D\uDEE1"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class EventLogAdapter : RecyclerView.Adapter<EventLogAdapter.EventViewHolder>() {

        private val events = mutableListOf<NetProtectionEvent>()

        fun addEvent(event: NetProtectionEvent) {
            events.add(0, event)
            if (events.size > 50) events.removeAt(events.size - 1)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return EventViewHolder(view)
        }

        override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
            val event = events[position]
            holder.bind(event)
        }

        override fun getItemCount(): Int = events.size

        class EventViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val text1: TextView = itemView.findViewById(android.R.id.text1)
            private val text2: TextView = itemView.findViewById(android.R.id.text2)

            fun bind(event: NetProtectionEvent) {
                val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                val timeStr = timeFormat.format(java.util.Date(event.timestamp))

                text1.text = "$timeStr ${event.message}"
                text1.textSize = 13f

                val color = when (event.type) {
                    EventType.ARP_SPOOF -> 0xFFF44336.toInt()
                    EventType.PORT_SCAN -> 0xFFFF9800.toInt()
                    EventType.CONNECTION_SPIKE -> 0xFFFF5722.toInt()
                    EventType.INFO -> 0xFF4CAF50.toInt()
                    EventType.ERROR -> 0xFFF44336.toInt()
                }
                text1.setTextColor(color)
                text2.visibility = View.GONE
            }
        }
    }
}
