package com.lsd.wififrankenstein.ui.handshakecapture

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentHandshakeCaptureSelectorBinding

class HandshakeCaptureSelectorFragment : Fragment() {

    private var _binding: FragmentHandshakeCaptureSelectorBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHandshakeCaptureSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val bssid = arguments?.getString("bssid").orEmpty()
        val ssid = arguments?.getString("ssid").orEmpty()
        val channel = arguments?.getString("channel").orEmpty()
        val iface = arguments?.getString("interface").orEmpty()

        binding.cardBettercap.setOnClickListener {
            findNavController().navigate(R.id.nav_bettercap)
        }

        binding.cardAirodump.setOnClickListener {
            val bundle = Bundle().apply {
                putString("bssid", bssid)
                putString("ssid", ssid)
                putString("channel", channel)
                putString("interface", iface)
            }
            findNavController().navigate(R.id.nav_airodump, bundle)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
