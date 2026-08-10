package com.lsd.wififrankenstein.ui.bettercap.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentBettercapApDetailBinding
import com.lsd.wififrankenstein.network.bettercap.BettercapAP
import com.lsd.wififrankenstein.ui.bettercap.BettercapViewModel
import com.lsd.wififrankenstein.util.Log

class BettercapAPDetailFragment : Fragment() {

    private var _binding: FragmentBettercapApDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BettercapViewModel by activityViewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return BettercapViewModel(requireActivity().application) as T
            }
        }
    }

    private lateinit var clientAdapter: BettercapClientAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBettercapApDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        clientAdapter = BettercapClientAdapter()
        binding.recyclerViewClients.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = clientAdapter
        }

        setupButtons()
        observeViewModel()
    }

    private var isDetailsExpanded = false

    private fun setupButtons() {
        binding.layoutDetailsHeader.setOnClickListener {
            isDetailsExpanded = !isDetailsExpanded
            binding.layoutDetailsContent.visibility =
                if (isDetailsExpanded) View.VISIBLE else View.GONE
            binding.dividerDetails.visibility = if (isDetailsExpanded) View.VISIBLE else View.GONE
            binding.iconToggleDetails.setImageResource(
                if (isDetailsExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }

        binding.buttonSelectAll.setOnClickListener {
            val allSelected =
                clientAdapter.getCheckedClients().size == (viewModel.selectedAp.value?.clients?.size
                    ?: 0)
            clientAdapter.setAllChecked(!allSelected)
        }

        binding.buttonDeauthSelected.setOnClickListener {
            val ap = viewModel.selectedAp.value ?: return@setOnClickListener
            val checked = clientAdapter.getCheckedClients()
            if (checked.isEmpty()) {
                viewModel.deauthAp(ap.mac)
                Toast.makeText(
                    requireContext(),
                    "Deauth all clients of ${ap.hostname}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                viewModel.deauthSelectedClients(ap.mac, checked)
                Toast.makeText(
                    requireContext(),
                    "Deauth ${checked.size} client(s)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.buttonDeauthAll.setOnClickListener {
            val ap = viewModel.selectedAp.value ?: return@setOnClickListener
            viewModel.deauthAp(ap.mac)
            Toast.makeText(requireContext(), "Deauth all: ${ap.hostname}", Toast.LENGTH_SHORT)
                .show()
        }

        binding.buttonAssoc.setOnClickListener {
            val ap = viewModel.selectedAp.value ?: return@setOnClickListener
            viewModel.assoc(ap.mac)
            Toast.makeText(requireContext(), "Assoc: ${ap.hostname}", Toast.LENGTH_SHORT).show()
        }

        binding.buttonDownloadHandshake.setOnClickListener {
            val ap = viewModel.selectedAp.value ?: return@setOnClickListener
            viewModel.saveHandshakeToStorage(ap)
            Toast.makeText(requireContext(), "Saving handshake...", Toast.LENGTH_SHORT).show()
        }

        binding.buttonViewInStorage.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_bettercap_detail_to_storage)
            } catch (e: Exception) {
                Log.e("BettercapDetail", "Navigation failed", e)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.selectedAp.observe(viewLifecycleOwner) { ap ->
            if (ap != null) bindApData(ap)
        }

        viewModel.wifiApList.observe(viewLifecycleOwner) { aps ->
            val current = viewModel.selectedAp.value
            if (current != null) {
                val updated = aps.find { it.mac == current.mac }
                if (updated != null) {
                    bindApData(updated)
                    clientAdapter.updateData(updated.clients)
                }
            }
        }

        viewModel.commandError.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                viewModel.consumeCommandError()
            }
        }
    }

    private fun bindDetailRow(include: View, label: String, value: String) {
        val labelView = include.findViewById<TextView>(R.id.row_label)
        val valueView = include.findViewById<TextView>(R.id.row_value)
        if (labelView != null) labelView.text = label
        if (valueView != null) {
            valueView.text = value.ifEmpty { "-" }
        }
    }

    private fun bindDetailRows(ap: BettercapAP) {
        val enc = ap.encryption.ifEmpty { "OPEN" }
        bindDetailRow(binding.rowEncryption.root, "Encryption", enc)
        bindDetailRow(binding.rowCipher.root, "Cipher", ap.cipher.ifEmpty { "-" })
        bindDetailRow(binding.rowAuth.root, "Auth", ap.authentication.ifEmpty { "-" })
        bindDetailRow(binding.rowFrequency.root, "Frequency", "${ap.frequency} MHz")
        bindDetailRow(binding.rowFirstSeen.root, "First Seen", ap.first_seen)
        bindDetailRow(binding.rowLastSeen.root, "Last Seen", ap.last_seen)
        bindDetailRow(binding.rowWpsVersion.root, "WPS Version", ap.wps["Version"] ?: "-")
        bindDetailRow(binding.rowWpsState.root, "WPS State", ap.wps["State"] ?: "-")
        val sent = if (ap.sent > 0) formatBytes(ap.sent) else "-"
        val recv = if (ap.received > 0) formatBytes(ap.received) else "-"
        bindDetailRow(binding.rowSent.root, "Sent", sent)
        bindDetailRow(binding.rowReceived.root, "Received", recv)
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
            bytes >= 1_000 -> "${bytes / 1_000} KB"
            else -> "$bytes B"
        }
    }

    private fun bindApData(ap: BettercapAP) {
        binding.textDetailSsid.text = ap.hostname.ifEmpty { "<hidden>" }
        binding.textDetailBssid.text = ap.mac
        binding.textDetailChannel.text = "Ch: ${ap.channel}"
        binding.textDetailRssi.text = "RSSI: ${ap.rssi}"
        binding.textDetailEncryption.text = ap.encryption.ifEmpty { "OPEN" }

        binding.textDetailVendor.text = "Vendor: ${ap.vendor}"
        bindDetailRows(ap)

        clientAdapter.updateData(ap.clients)
        binding.textNoClients.visibility = if (ap.clients.isEmpty()) View.VISIBLE else View.GONE

        val hasHandshake = ap.handshake
        binding.textHandshakeStatus.text = if (hasHandshake) {
            "Handshake: captured"
        } else {
            "Handshake: not captured"
        }
        binding.textHandshakeStatus.setTextColor(
            if (hasHandshake) android.graphics.Color.rgb(76, 175, 80)
            else android.graphics.Color.GRAY
        )
        binding.layoutHandshakeActions.visibility = if (hasHandshake) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
